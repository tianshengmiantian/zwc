package com.westart.ai.westart.controller;

import com.westart.ai.westart.service.BailianService;
import com.westart.ai.westart.service.QWeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/status")
public class IntegrationStatusController {

    private final BailianService bailianService;
    private final QWeatherService qWeatherService;

    public IntegrationStatusController(
            BailianService bailianService,
            QWeatherService qWeatherService
    ) {
        this.bailianService = bailianService;
        this.qWeatherService = qWeatherService;
    }

    @GetMapping("/live")
    public Map<String, Object> live(@RequestParam(defaultValue = "北京") String location) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("warning", "此接口会实际调用百炼和天气服务，可能产生少量用量");
        result.put("bailian", checkBailian());
        result.put("qweather", checkWeather(location));
        return result;
    }

    private Map<String, Object> checkBailian() {
        try {
            BailianService.AiResult response = bailianService.askText("仅回复 OK");
            return Map.of("success", true, "model", response.model());
        } catch (Exception exception) {
            return Map.of("success", false, "message", safeMessage(exception));
        }
    }

    private Map<String, Object> checkWeather(String location) {
        try {
            QWeatherService.CurrentWeather response = qWeatherService.current(location);
            return Map.of(
                    "success", true,
                    "location", response.location(),
                    "weather", response.weather(),
                    "temperatureCelsius", response.temperatureCelsius()
            );
        } catch (Exception exception) {
            return Map.of("success", false, "message", safeMessage(exception));
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
