package com.example.booking_service.Controller;

import com.example.booking_service.DTO.BookingDetailsDTO;
import com.example.booking_service.DTO.BookingRequestDTO;
import com.example.booking_service.DTO.BookingResponseDTO;
import com.example.booking_service.DTO.MyBookingDTO;
import com.example.booking_service.Service.BookingService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/booking")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingResponseDTO> bookTicket(
            Authentication authentication,
            @Valid @RequestBody BookingRequestDTO request) {

        BookingResponseDTO response;
        try {
            response = bookingService.bookTicket(authentication, request);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race to create the train+date schedule row on its first
            // booking; that row now exists (the winner committed it), so retrying
            // once takes the normal find-and-lock path. See BookingService
            // .getOrCreateScheduleForUpdate for why this isn't retried in-place.
            response = bookingService.bookTicket(authentication, request);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    public ResponseEntity<Page<MyBookingDTO>> getMyBookings(
            Authentication authentication,
            @PageableDefault(size = 20, sort = "bookingTime", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<MyBookingDTO> response = bookingService.getUserBookings(authentication, pageable);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingDetailsDTO> getBooking(
            @PathVariable Long bookingId,
            Authentication authentication) {

        BookingDetailsDTO response =
                BookingDetailsDTO.fromEntity(bookingService.getOwnedBooking(bookingId, authentication));

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{bookingId}")
    public ResponseEntity<Void> cancelBooking(
            @PathVariable Long bookingId,
            Authentication authentication) {

        bookingService.cancelBooking(bookingId, authentication);

        return ResponseEntity.noContent().build();
    }
}