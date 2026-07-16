package com.westart.ai.westart.controller;

import com.westart.ai.westart.service.QWeatherService;
import com.westart.ai.westart.service.QWeatherService.CurrentWeather;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final QWeatherService qWeatherService;

    public WeatherController(QWeatherService qWeatherService) {
        this.qWeatherService = qWeatherService;
    }

    @GetMapping("/current")
    public CurrentWeather current(@RequestParam String location) {
        return qWeatherService.current(location);
    }
}
