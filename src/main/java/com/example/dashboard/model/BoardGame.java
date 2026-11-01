package com.example.dashboard.model;

import lombok.Data;

@Data
public class BoardGame {
    private String name;
    private int year;
    private int rank;
    private double average;
    private double bayesAverage;
    private int usersRated;

    public String getDecadeLabel() {
        return (year / 10) * 10 + "s";
    }
}
