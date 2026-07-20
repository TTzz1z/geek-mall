package com.geekmall.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

record SkuSnapshot(long skuId,long spuId,String skuCode,BigDecimal salePrice,BigDecimal marketPrice,String skuStatus,
                   String title,String subtitle,String coverUrl,long merchantId,long shopId,String auditStatus,String saleStatus,String shopName) {}
record WarehouseAvailability(long warehouseId,String warehouseName,String regionCode,int priority,int availableQty) {}
record StockAvailability(long merchantId,long skuId,int requested,int availableQty,boolean available,List<WarehouseAvailability> warehouses) {}
record StockAllocation(String reservationNo,long orderId,String orderNo,long merchantId,long warehouseId,long skuId,int quantity,String status,Instant expiresAt) {}
record CheckoutAddress(Long addressId,String receiver,String mobile,String regionCode,String detail) {}
record PromotionLine(String type,String sourceId,String label,BigDecimal amount) {}
record CheckoutItem(long cartItemId,long spuId,long skuId,long merchantId,long shopId,String shopName,String title,String coverUrl,
                    String skuCode,BigDecimal unitPrice,int quantity,BigDecimal subtotal,BigDecimal discountAmount,BigDecimal payableAmount) {}
record CheckoutWarehousePlan(long warehouseId,String warehouseName,long skuId,int quantity) {}
record CheckoutShopGroup(long shopId,long merchantId,String shopName,BigDecimal totalAmount,BigDecimal discountAmount,
                         BigDecimal payableAmount,List<CheckoutItem> items,List<CheckoutWarehousePlan> warehousePlans) {}
record CheckoutPayload(String checkoutToken,Instant expiresAt,CheckoutAddress address,BigDecimal totalAmount,BigDecimal discountAmount,
                       BigDecimal freightAmount,BigDecimal payableAmount,String discountType,String discountSourceId,
                       Long userCouponId,Integer pointsUsed,List<PromotionLine> promotionLines,
                       List<CheckoutItem> items,List<CheckoutShopGroup> shopGroups) {}
