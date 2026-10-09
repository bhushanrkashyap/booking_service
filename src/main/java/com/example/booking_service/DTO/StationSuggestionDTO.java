package com.example.booking_service.DTO;

public class StationSuggestionDTO {
    private String name;
    private String code;

    public StationSuggestionDTO() {
    }

    public StationSuggestionDTO(String name, String code) {
        this.name = name;
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}

