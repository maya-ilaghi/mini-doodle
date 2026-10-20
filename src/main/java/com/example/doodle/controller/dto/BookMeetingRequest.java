package com.example.doodle.controller.dto;

import java.util.List;

public record BookMeetingRequest(String title, String description, List<String> participants) {}

