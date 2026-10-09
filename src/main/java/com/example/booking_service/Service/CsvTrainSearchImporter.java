package com.example.booking_service.Service;

import com.example.booking_service.model.TrainSearchDocument;
import jakarta.annotation.PostConstruct;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class CsvTrainSearchImporter {
    private static final Logger log = LoggerFactory.getLogger(CsvTrainSearchImporter.class);
    private static final int BATCH_SIZE = 1000;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ElasticsearchOperations elasticsearchOperations;
    private final boolean importEnabled;
    private final boolean importOnStartup;
    private final boolean forceImport;
    private final String csvPath;

    public CsvTrainSearchImporter(
            ElasticsearchOperations elasticsearchOperations,
            @Value("${app.elasticsearch.import.enabled:true}") boolean importEnabled,
            @Value("${app.elasticsearch.import.on-startup:true}") boolean importOnStartup,
            @Value("${app.elasticsearch.import.force:false}") boolean forceImport,
            @Value("${app.elasticsearch.import.csv-path:classpath:railway-data.csv}") String csvPath) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.importEnabled = importEnabled;
        this.importOnStartup = importOnStartup;
        this.forceImport = forceImport;
        this.csvPath = csvPath;
    }

    @PostConstruct
    public void importDataOnStartup() {
        if (!importEnabled) {
            log.info("Elasticsearch import is disabled via app.elasticsearch.import.enabled=false");
            return;
        }
        if (!importOnStartup) {
            log.info("Elasticsearch import on startup is disabled via app.elasticsearch.import.on-startup=false");
            return;
        }
        importCsv();
    }

    public int importCsv() {
        Resource resource = resolveCsvResource();
        if (resource == null || !resource.exists()) {
            throw new IllegalStateException("Train CSV resource not found for Elasticsearch import: " + csvPath);
        }

        IndexOperations indexOps = elasticsearchOperations.indexOps(TrainSearchDocument.class);
        if (forceImport) {
            try {
                indexOps.delete();
                log.warn("Force-reindex enabled: deleted existing Elasticsearch index '{}' before import", TrainSearchDocument.INDEX_NAME);
            } catch (DataAccessException ex) {
                log.warn("Force-reindex requested but index '{}' could not be deleted cleanly: {}", TrainSearchDocument.INDEX_NAME, ex.getMessage());
            }
        }

        ensureIndexExists(indexOps);

        if (!forceImport) {
            try {
                Long existingDocuments = elasticsearchOperations.count(
                        NativeQuery.builder()
                                .withQuery(query -> query.matchAll(matchAll -> matchAll))
                                .build(),
                        TrainSearchDocument.class);
                if (existingDocuments != null && existingDocuments > 0) {
                    log.info("Elasticsearch index '{}' already contains {} document(s); skipping import. " +
                                    "Set app.elasticsearch.import.force=true to re-import the CSV on the next startup.",
                            TrainSearchDocument.INDEX_NAME, existingDocuments);
                    return 0;
                }
            } catch (DataAccessException ex) {
                log.warn("Could not inspect existing documents in Elasticsearch index '{}': {}. Continuing with import.",
                        TrainSearchDocument.INDEX_NAME, ex.getMessage());
            }
        }

        int rowsRead = 0;
        int documentsIndexed = 0;
        int documentsSkipped = 0;
        int malformedRows = 0;
        List<TrainSearchDocument> batch = new ArrayList<>();

        log.info("Starting train CSV Elasticsearch import");

        try (InputStream inputStream = resource.getInputStream();
             Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreSurroundingSpaces(true)
                     .setQuoteMode(org.apache.commons.csv.QuoteMode.MINIMAL)
                     .build()
                     .parse(reader)) {

            for (CSVRecord record : parser) {
                rowsRead++;
                TrainSearchDocument document;
                try {
                    document = mapRecord(record, rowsRead);
                } catch (Exception ex) {
                    malformedRows++;
                    log.warn("Malformed row {} in CSV '{}': {}", rowsRead, resource.getFilename(), ex.getMessage());
                    continue;
                }

                if (document == null) {
                    documentsSkipped++;
                    continue;
                }

                document.setId(document.getPnrNumber());
                batch.add(document);

                if (batch.size() >= BATCH_SIZE) {
                    documentsIndexed += indexBatch(batch);
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                documentsIndexed += indexBatch(batch);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to import train CSV into Elasticsearch: " + resource.getFilename(), ex);
        }

        log.info("CSV rows read: {}", rowsRead);
        log.info("Documents indexed: {}", documentsIndexed);
        log.info("Documents skipped: {}", documentsSkipped + malformedRows);
        log.info("Elasticsearch index: {}", TrainSearchDocument.INDEX_NAME);
        return documentsIndexed;
    }

    private int indexBatch(List<TrainSearchDocument> documents) {
        List<IndexQuery> indexQueries = new ArrayList<>();
        for (TrainSearchDocument document : documents) {
            indexQueries.add(new IndexQueryBuilder()
                    .withId(document.getId())
                    .withObject(document)
                    .build());
        }

        try {
            elasticsearchOperations.bulkIndex(indexQueries, TrainSearchDocument.class);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to bulk index " + documents.size() + " train document(s) into Elasticsearch index '" +
                            TrainSearchDocument.INDEX_NAME + "'",
                    ex
            );
        }
        return documents.size();
    }

    private void ensureIndexExists(IndexOperations indexOps) {
        try {
            indexOps.create();
            log.info("Created Elasticsearch index '{}' for train search", TrainSearchDocument.INDEX_NAME);
        } catch (DataAccessException ex) {
            log.debug("Elasticsearch index '{}' already exists or could not be created on this pass: {}",
                    TrainSearchDocument.INDEX_NAME, ex.getMessage());
        }
    }

    private TrainSearchDocument mapRecord(CSVRecord record, int rowNumber) {
        String pnrNumber = trim(record.get("PNR Number"));
        if (pnrNumber.isBlank()) {
            throw new IllegalArgumentException("row " + rowNumber + ": PNR Number is missing");
        }

        Integer trainNumber = parseInteger(record.get("Train Number"), "Train Number", rowNumber);
        LocalDate journeyDate = parseDate(record.get("Date of Journey"), "Date of Journey", rowNumber);
        String classOfTravel = trim(record.get("Class of Travel"));
        String quota = trim(record.get("Quota"));
        String sourceStation = trim(record.get("Source Station"));
        String destinationStation = trim(record.get("Destination Station"));
        if (sourceStation.isBlank() || destinationStation.isBlank()) {
            throw new IllegalArgumentException("row " + rowNumber + ": source or destination station is missing");
        }

        LocalDate bookingDate = parseDate(record.get("Booking Date"), "Booking Date", rowNumber);
        String currentStatus = trim(record.get("Current Status"));
        Integer numberOfPassengers = parseInteger(record.get("Number of Passengers"), "Number of Passengers", rowNumber);
        String ageOfPassengers = trim(record.get("Age of Passengers"));
        String bookingChannel = trim(record.get("Booking Channel"));
        Double travelDistance = parseDouble(record.get("Travel Distance"), "Travel Distance", rowNumber);
        Integer numberOfStations = parseInteger(record.get("Number of Stations"), "Number of Stations", rowNumber);
        Double travelTime = parseDouble(record.get("Travel Time"), "Travel Time", rowNumber);
        String trainType = trim(record.get("Train Type"));
        Integer seatAvailability = parseInteger(record.get("Seat Availability"), "Seat Availability", rowNumber);
        String specialConsiderations = trim(record.get("Special Considerations"));
        String holidayOrPeakSeason = trim(record.get("Holiday or Peak Season"));
        String waitlistPosition = trim(record.get("Waitlist Position"));
        String confirmationStatus = trim(record.get("Confirmation Status"));

        TrainSearchDocument document = new TrainSearchDocument();
        document.setPnrNumber(pnrNumber);
        document.setTrainNumber(trainNumber);
        document.setJourneyDate(journeyDate);
        document.setClassOfTravel(classOfTravel);
        document.setQuota(quota);
        document.setSourceStation(sourceStation);
        document.setSourceStationNormalized(normalizeKey(sourceStation));
        document.setDestinationStation(destinationStation);
        document.setDestinationStationNormalized(normalizeKey(destinationStation));
        document.setBookingDate(bookingDate);
        document.setCurrentStatus(currentStatus);
        document.setNumberOfPassengers(numberOfPassengers);
        document.setAgeOfPassengers(ageOfPassengers);
        document.setBookingChannel(bookingChannel);
        document.setTravelDistance(travelDistance);
        document.setNumberOfStations(numberOfStations);
        document.setTravelTime(travelTime);
        document.setTrainType(trainType);
        document.setSeatAvailability(seatAvailability);
        document.setSpecialConsiderations(specialConsiderations);
        document.setHolidayOrPeakSeason(holidayOrPeakSeason);
        document.setWaitlistPosition(waitlistPosition);
        document.setConfirmationStatus(confirmationStatus);
        return document;
    }

    private Resource resolveCsvResource() {
        if (csvPath == null || csvPath.isBlank()) {
            return new ClassPathResource("railway-data.csv");
        }
        String location = csvPath.trim();
        if (location.startsWith("classpath:")) {
            location = location.substring("classpath:".length());
            return new ClassPathResource(location);
        }
        if (location.startsWith("file:")) {
            location = location.substring("file:".length());
        }
        return new FileSystemResource(location);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static Integer parseInteger(String rawValue, String fieldName, int rowNumber) {
        String value = trim(rawValue);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value.replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("row " + rowNumber + ": invalid integer for " + fieldName + ": '" + rawValue + "'");
        }
    }

    private static Double parseDouble(String rawValue, String fieldName, int rowNumber) {
        String value = trim(rawValue);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value.replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("row " + rowNumber + ": invalid numeric value for " + fieldName + ": '" + rawValue + "'");
        }
    }

    private static LocalDate parseDate(String rawValue, String fieldName, int rowNumber) {
        String value = trim(rawValue);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE_FORMATTER);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("row " + rowNumber + ": invalid date for " + fieldName + ": '" + rawValue + "'");
        }
    }
}
