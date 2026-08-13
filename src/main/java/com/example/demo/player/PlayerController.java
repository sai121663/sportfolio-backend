package com.example.demo.player;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/players")
public class PlayerController {

    private final PlayerRepository playerRepository;
    private final PlayerCardService playerCardService;

    public PlayerController(PlayerRepository playerRepository, PlayerCardService playerCardService) {
        this.playerRepository = playerRepository;
        this.playerCardService = playerCardService;
    }

    @GetMapping
    public List<PlayerCardDto> getAllPlayers() {
        return playerCardService.buildCards(playerRepository.findAll());
    }
}