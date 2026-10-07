package com.autism.seedcracker.chatgames;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.autism.seedcracker.chatgames.ChatGameSolver.Kind;

import static org.junit.jupiter.api.Assertions.*;

class ChatGameSolverTest {

    private final ChatGameSolver solver = new ChatGameSolver(List.of(
        "diamond", "emerald", "pickaxe", "creeper", "netherite", "golden apple", "beacon", "anvil", "naive"));

    @Test
    void solvesCommonMathPrompts() {
        assertEquals("33", solver.solve("[ChatGames] Solve: 12 + 7 * 3", false).text());
        assertEquals("30", solver.solve("What is 5 x 6?", false).text());
        assertEquals("2.5", solver.solve("Calculate 10 / 4 first to answer wins!", false).text());
        assertEquals(Kind.MATH, solver.solve("Solve 2^10", false).kind());
    }

    @Test
    void neverReadsWordsDatesOrTimesAsMath() {
        assertNull(ChatGameSolver.extractMath("max 5 players in the box"));
        assertNull(ChatGameSolver.extractMath("event at 10:30 on 12/4"));
        assertNull(ChatGameSolver.extractMath("season started 2026-10-02"));
        assertEquals("3*4", ChatGameSolver.extractMath("what is 3x4").replace(" ", ""));
    }

    @Test
    void unscramblesAndKeepsCase() {
        assertEquals("diamond", solver.solve("Unscramble the word: mdnaiod", false).text());
        assertEquals("CREEPER", solver.solve("UNSCRAMBLE: REPCERE", false).text());
        assertEquals("golden apple", solver.solve("Unscramble 'pelpa nolged'", false).text());
        assertNull(solver.solve("Unscramble: zzqqxx", false), "unknown word stays silent");
    }

    @Test
    void scrambleThatIsAlsoAWordReturnsTheOther() {
        ChatGameSolver s = new ChatGameSolver(List.of("listen", "silent"));
        assertEquals("listen", s.unscramble("silent"), "the scramble itself is never the answer");
        assertEquals("anvil", solver.unscramble("naliv"));
    }

    @Test
    void retypeReverseAndFill() {
        assertEquals("Pumpkin Pie", solver.solve("First to type \"Pumpkin Pie\" wins!", false).text());
        assertEquals("diamond", solver.solve("Reverse: dnomaid", false).text());
        assertEquals("emerald", solver.solve("Fill in the missing letters: e_er_ld", false).text());
        assertNull(solver.fill("___"), "too many matches = ambiguous");
    }

    @Test
    void typeWithMathIsMathNotRetype() {
        assertEquals("10", solver.solve("Type the answer to 5+5", false).text());
    }

    @Test
    void wordProblemsOnlyWhenAllowed() {
        assertNull(solver.solve("What is five plus three", false));
        assertEquals("8", solver.solve("What is five plus three", true).text());
    }

    @Test
    void learnsTriviaFromReveals() {
        String q = "Trivia: What mob drops gunpowder?";
        assertNull(solver.solve(q, false));
        solver.learn(q, ChatLine.revealedAnswer("Nobody got it! The answer was Creeper."));
        ChatGameSolver.Answer a = solver.solve("TRIVIA - what mob drops gunpowder", false);
        assertEquals(Kind.TRIVIA, a.kind());
        assertEquals("Creeper", a.text());
    }

    @Test
    void revealAndRoundEndParsing() {
        assertEquals("diamond", ChatLine.revealedAnswer("Steve won! The word was 'diamond'."));
        assertEquals("42", ChatLine.revealedAnswer("The correct answer is: 42"));
        assertNull(ChatLine.revealedAnswer("Steve joined the game"));
        assertTrue(ChatLine.isRoundOver("Nobody answered in time!"));
        assertTrue(ChatLine.isRoundOver("Steve won the chat game!"));
        assertFalse(ChatLine.isRoundOver("First to unscramble 'mdnaoid' wins $500!"), "a prompt promising a win isn't a win");
    }

    @Test
    void playerChatIsIgnoredSoNobodyCanBaitReplies() {
        List<String> online = List.of("Steve", "Alex_99");
        assertTrue(ChatLine.isPlayerChat("<Steve> unscramble: mdnaoid", online));
        assertTrue(ChatLine.isPlayerChat("[VIP] Alex_99: what is 5+5", online));
        assertTrue(ChatLine.isPlayerChat("Steve \u00BB first to type 'lol' wins", online));
        assertFalse(ChatLine.isPlayerChat("[ChatGames] Unscramble: mdnaoid", online));
        assertFalse(ChatLine.isPlayerChat("Games: what is 5+5", online), "the prefix isn't an online player");
    }

    @Test
    void aiRepliesAreTrimmedButKeepWords() {
        assertEquals("diamond", AiRouter.sanitize("The answer is: \"diamond\".\nBecause..."));
        assertEquals("42", AiRouter.sanitize("42"));
        assertEquals("Golden Apple", AiRouter.sanitize("Golden Apple"));
        assertNull(AiRouter.sanitize("   "));
    }
}
