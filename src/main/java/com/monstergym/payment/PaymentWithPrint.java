package com.monstergym.payment;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.monstergym.printing.PrintInfo;

/** Respuesta de un cobro: los campos del cobro más "print" con el trabajo del ticket automático. */
public record PaymentWithPrint(@JsonUnwrapped PaymentReceipt receipt, PrintInfo print) {
}
