package com.ecommerce.order_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** One order line. sku, productName and unitPrice are a snapshot taken when the order was placed. */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(length = 64)
    private String sku;

    @Column(name = "product_name", length = 255)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    /** Factory method: computes line total = unit price x quantity, rounded to 2 decimals. */
    public static OrderItem of(Long productId, String sku, String productName, BigDecimal unitPrice, int quantity) {
        OrderItem item = new OrderItem();
        item.productId = productId;
        item.sku = sku;
        item.productName = productName;
        item.unitPrice = unitPrice.setScale(2, RoundingMode.HALF_UP);
        item.quantity = quantity;
        item.lineTotal = item.unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        return item;
    }
}
