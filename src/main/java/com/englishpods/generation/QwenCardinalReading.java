package com.englishpods.generation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.regex.Pattern;

final class QwenCardinalReading {
    private static final Pattern INTEGER = Pattern.compile("0|[1-9][0-9]{0,3}");
    private static final Pattern TOKEN = Pattern.compile("[\\s,;:!?(\\[{\"'“‘]*([A-Za-z]+(?:(?:\\s+|-)[A-Za-z]+)*)"
            + "[\\s.,;:!?)}\\]\"'”’]*");
    private static final Pattern SEPARATOR = Pattern.compile("[\\s-]+");
    private static final String[] SMALL = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
            "eighty", "ninety"};

    private QwenCardinalReading() {}

    static int lastMatchingToken(String text, StoryText.Range sourceWord, JsonNode tokens, int firstToken) {
        String digits = text.substring(sourceWord.start(), sourceWord.end());
        if (!INTEGER.matcher(digits).matches() || !hasPlainNumberBoundaries(text, sourceWord)) return -1;

        int value = Integer.parseInt(digits);
        String cardinal = spell(value, false);
        int last = match(tokens, firstToken, cardinal);
        if (last >= 0) return last;

        String cardinalWithAnd = spell(value, true);
        return cardinalWithAnd.equals(cardinal) ? -1 : match(tokens, firstToken, cardinalWithAnd);
    }

    private static boolean hasPlainNumberBoundaries(String text, StoryText.Range word) {
        // Exclude signs, currencies, fractions, and grouped or decimal numbers.
        String whitespace = " \t\r\n";
        if (word.start() > 0 && (whitespace + "([{\"'“‘").indexOf(text.charAt(word.start() - 1)) < 0) return false;
        if (word.end() == text.length()) return true;
        char next = text.charAt(word.end());
        if ((whitespace + ".,;:!?)]}\"'”’").indexOf(next) < 0) return false;
        return ".,:".indexOf(next) < 0 || word.end() + 1 == text.length()
                || !Character.isDigit(text.codePointAt(word.end() + 1));
    }

    private static String spell(int value, boolean withAnd) {
        if (value < 20) return SMALL[value];
        if (value < 100) {
            int ones = value % 10;
            return TENS[value / 10] + (ones == 0 ? "" : " " + SMALL[ones]);
        }

        int divisor = value < 1000 ? 100 : 1000;
        String leading = SMALL[value / divisor] + (divisor == 100 ? " hundred" : " thousand");
        int remainder = value % divisor;
        if (remainder == 0) return leading;
        return leading + (withAnd && remainder < 100 ? " and " : " ") + spell(remainder, withAnd);
    }

    private static int match(JsonNode tokens, int firstToken, String cardinal) {
        int start = 0;
        for (int token = firstToken; token < tokens.size(); token++) {
            String text = tokens.get(token).path("text").asText("");
            if (StoryText.lexical(text).isEmpty()) continue;
            var matcher = TOKEN.matcher(text);
            if (!matcher.matches()) return -1;
            String spelling = SEPARATOR.matcher(matcher.group(1).toLowerCase(Locale.ROOT)).replaceAll(" ");
            if (!cardinal.startsWith(spelling, start)) return -1;
            int end = start + spelling.length();
            if (end == cardinal.length()) return token;
            if (cardinal.charAt(end) != ' ') return -1;
            start = end + 1;
        }
        return -1;
    }
}
