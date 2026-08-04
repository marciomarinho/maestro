package dev.maestro.payment.core;

import dev.maestro.domain.payment.CardNetwork;
import dev.maestro.payment.web.ApiException;
import java.util.Locale;

/**
 * Non-sensitive card metadata, read from the vault token.
 *
 * <p>The platform never sees a card number (ADR-0011), but routing decisions need the
 * network — a corridor is a network and a currency — and merchants need the last four
 * digits to recognise a payment. In a real deployment the tokenisation vault returns
 * this alongside the token. Here the simulated vault encodes it in the token itself,
 * as {@code tok_<network>_<last4>}, so no vault call is needed and the token stays
 * self-describing in a log line.
 */
public record CardToken(String token, CardNetwork network, String last4) {

    public static CardToken parse(String token) {
        String[] parts = token.split("_");
        if (parts.length != 3) {
            throw ApiException.unprocessable(
                    "invalid_card_token", "Card token must be of the form tok_<network>_<last4>");
        }
        return new CardToken(token, networkOf(parts[1]), parts[2]);
    }

    private static CardNetwork networkOf(String segment) {
        return switch (segment.toLowerCase(Locale.ROOT)) {
            case "visa" -> CardNetwork.VISA;
            case "mc", "mastercard" -> CardNetwork.MASTERCARD;
            case "amex" -> CardNetwork.AMEX;
            case "eftpos" -> CardNetwork.EFTPOS;
            default -> throw ApiException.unprocessable(
                    "unsupported_card_network", "Unsupported card network: " + segment);
        };
    }
}
