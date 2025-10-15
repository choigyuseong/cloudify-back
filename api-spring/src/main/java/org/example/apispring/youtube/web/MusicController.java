package org.example.apispring.youtube.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class MusicController {

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @PostMapping("/recommend")
    public String recommend(@RequestParam String userInput, Model model) {
        String recommendation = getRecommendation(userInput);
        model.addAttribute("userInput", userInput);
        model.addAttribute("recommendation", recommendation);
        return "result";
    }

    private String getRecommendation(String input) {
        input = input.toLowerCase();
        if (input.contains("잔잔") || input.contains("슬픈")) {
            return "🎵 추천: IU - 밤편지 (YouTube Music)";
        } else if (input.contains("신나는") || input.contains("댄스")) {
            return "🎵 추천: NewJeans - Super Shy (YouTube Music)";
        } else {
            return "🎵 추천: BTS - Dynamite (YouTube Music)";
        }
    }
}