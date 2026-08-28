package featurecat.lizzie.enginegame;

public record OpeningStanding(
    int openingIndex,
    int firstWins,
    int firstWinsAsBlack,
    int firstWinsAsWhite,
    int secondWins,
    int secondWinsAsBlack,
    int secondWinsAsWhite) {}
