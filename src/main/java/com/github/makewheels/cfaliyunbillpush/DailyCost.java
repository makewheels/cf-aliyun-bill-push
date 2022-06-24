package com.github.makewheels.cfaliyunbillpush;

import lombok.Data;

import java.util.Map;

@Data
public class DailyCost {
    private int year;
    private int month;
    private int day;
    private Map<String, Integer> map;

}
