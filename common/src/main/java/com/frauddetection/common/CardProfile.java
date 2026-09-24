package com.frauddetection.common;

/**
 * A simulated card.
 *
 * @param homeCity      where the card holder usually spends
 * @param typicalAmount typical transaction amount in VND; random transactions and the
 *                      30-day history are generated around this value
 */
public record CardProfile(String cardId, City homeCity, long typicalAmount) {
}
