package com.megagraph.shop.payment;

import com.megagraph.shop.catalog.ProductSku;
import com.megagraph.shop.catalog.ProductSkuRepository;
import com.megagraph.shop.commerce.ReservationStatus;
import com.megagraph.shop.commerce.StockReservation;
import com.megagraph.shop.commerce.StockReservationRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class InventoryReservationService {

    private final StockReservationRepository reservations;
    private final ProductSkuRepository skus;

    public InventoryReservationService(
            StockReservationRepository reservations,
            ProductSkuRepository skus
    ) {
        this.reservations = reservations;
        this.skus = skus;
    }

    public void confirm(Long orderId) {
        reservations.findByOrderIdForUpdate(orderId).forEach(StockReservation::confirm);
    }

    public void release(Long orderId) {
        List<StockReservation> reserved = reservations.findByOrderIdForUpdate(orderId).stream()
                .filter(value -> value.getStatus() == ReservationStatus.RESERVED)
                .toList();
        changeStock(reserved, false);
    }

    public void restock(Long orderId) {
        List<StockReservation> confirmed = reservations.findByOrderIdForUpdate(orderId).stream()
                .filter(value -> value.getStatus() == ReservationStatus.CONFIRMED)
                .toList();
        changeStock(confirmed, true);
    }

    private void changeStock(List<StockReservation> selected, boolean restock) {
        if (selected.isEmpty()) {
            return;
        }
        List<Long> skuIds = selected.stream().map(StockReservation::getSkuId).distinct().sorted().toList();
        Map<Long, ProductSku> lockedSkus = skus.findAllByIdForUpdate(skuIds).stream()
                .collect(Collectors.toMap(ProductSku::getId, Function.identity()));
        for (StockReservation reservation : selected) {
            boolean changed = restock ? reservation.restock() : reservation.release();
            if (changed) {
                ProductSku sku = lockedSkus.get(reservation.getSkuId());
                if (sku == null) {
                    throw new IllegalStateException("Reserved SKU no longer exists");
                }
                sku.release(reservation.getQuantity());
            }
        }
    }
}
