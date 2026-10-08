package com.monstergym.sale;

import java.util.List;

/** Venta del refri con sus partidas. Dinero en centavos; unitPriceCents es el precio al momento de vender. */
public record SaleReceipt(
        long id,
        String folio,
        String createdAt,
        String method,
        long totalCents,
        String employeeName,
        long shiftId,
        String voidedAt,
        String voidReason,
        List<Item> items) {

    public record Item(long productId, String name, int qty, long unitPriceCents, long subtotalCents) {
    }
}
