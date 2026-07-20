package com.geekmall.trade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class CheckoutService {
    private final JdbcTemplate jdbc;
    private final CartService cart;
    private final ProductSnapshotClient products;
    private final InventoryClient inventory;
    private final MarketingClient marketing;
    private final MemberClient member;
    private final ObjectMapper mapper;
    private final long ttlSeconds;

    CheckoutService(JdbcTemplate jdbc, CartService cart, ProductSnapshotClient products, InventoryClient inventory,
                    MarketingClient marketing, MemberClient member, ObjectMapper mapper,
                    @Value("${geek.checkout-ttl-seconds:900}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.cart = cart;
        this.products = products;
        this.inventory = inventory;
        this.marketing = marketing;
        this.member = member;
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
    }

    @Transactional
    CheckoutPayload preview(long userId, CheckoutController.PreviewRequest request) {
        CheckoutAddress address = resolveAddress(userId, request);
        List<Map<String, Object>> cartRows = cart.selectedRows(userId, request.cartItemIds());
        if (cartRows.isEmpty()) throw new BusinessException("TR40003", "请选择需要结算的商品");
        List<Long> skuIds = cartRows.stream().map(r -> number(r.get("sku_id"))).toList();
        Map<Long, SkuSnapshot> skus = products.snapshots(skuIds).stream().collect(Collectors.toMap(SkuSnapshot::skuId, Function.identity()));
        List<Map<String, Object>> availabilityRequest = cartRows.stream()
                .map(r -> Map.<String, Object>of("merchantId", number(r.get("merchant_id")), "skuId", number(r.get("sku_id")), "quantity", integer(r.get("quantity")))).toList();
        Map<Long, StockAvailability> stock = inventory.availability(availabilityRequest).stream().collect(Collectors.toMap(StockAvailability::skuId, Function.identity()));
        String levelCode = String.valueOf(member.profile(userId).getOrDefault("levelCode", "NORMAL"));
        BigDecimal memberRate = "GOLD".equals(levelCode) ? new BigDecimal("0.05") : BigDecimal.ZERO;

        List<CheckoutItem> raw = new ArrayList<>();
        BigDecimal merchandise = BigDecimal.ZERO;
        BigDecimal memberDiscount = BigDecimal.ZERO;
        for (Map<String, Object> row : cartRows) {
            long skuId = number(row.get("sku_id"));
            SkuSnapshot sku = skus.get(skuId);
            if (sku == null || !"APPROVED".equals(sku.auditStatus()) || !"ON_SALE".equals(sku.saleStatus()) || !"ENABLED".equals(sku.skuStatus()))
                throw new BusinessException("TR40903", "商品状态已变化，请返回购物车确认");
            int quantity = integer(row.get("quantity"));
            StockAvailability available = stock.get(skuId);
            if (available == null || available.availableQty() < quantity) throw new BusinessException("TR40902", sku.title() + " 库存不足");
            BigDecimal subtotal = sku.salePrice().multiply(BigDecimal.valueOf(quantity));
            BigDecimal lineMember = subtotal.multiply(memberRate).setScale(2, RoundingMode.DOWN);
            merchandise = merchandise.add(subtotal);
            memberDiscount = memberDiscount.add(lineMember);
            raw.add(new CheckoutItem(number(row.get("id")), sku.spuId(), sku.skuId(), sku.merchantId(), sku.shopId(), sku.shopName(), sku.title(), sku.coverUrl(), sku.skuCode(), sku.salePrice(), quantity, subtotal, lineMember, subtotal.subtract(lineMember)));
        }

        List<PromotionLine> promotionLines = new ArrayList<>();
        if (memberDiscount.signum() > 0) promotionLines.add(new PromotionLine("MEMBER_PRICE", levelCode, "会员价优惠", memberDiscount));

        BigDecimal afterMember = merchandise.subtract(memberDiscount);
        Map<String, Object> quote = marketing.quote(userId, afterMember, request.userCouponId());
        BigDecimal promoDiscount = new BigDecimal(String.valueOf(quote.getOrDefault("discountAmount", "0")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> quoteLines = (List<Map<String, Object>>) quote.getOrDefault("promotionLines", List.of());
        for (Map<String, Object> line : quoteLines) {
            promotionLines.add(new PromotionLine(String.valueOf(line.get("type")), String.valueOf(line.get("sourceId")), String.valueOf(line.get("label")), new BigDecimal(String.valueOf(line.get("amount")))));
        }

        int pointsToUse = request.pointsToUse() == null ? 0 : request.pointsToUse();
        BigDecimal pointsDiscount = BigDecimal.ZERO;
        if (pointsToUse > 0) {
            int balance = ((Number) member.profile(userId).getOrDefault("pointBalance", 0)).intValue();
            if (pointsToUse > balance) throw new BusinessException("TR40910", "积分不足");
            pointsDiscount = BigDecimal.valueOf(pointsToUse).divide(new BigDecimal("100"), 2, RoundingMode.DOWN);
            BigDecimal cap = afterMember.subtract(promoDiscount).multiply(new BigDecimal("0.5"));
            if (pointsDiscount.compareTo(cap) > 0) {
                pointsDiscount = cap;
                pointsToUse = pointsDiscount.multiply(new BigDecimal("100")).intValue();
            }
            if (pointsDiscount.signum() > 0) promotionLines.add(new PromotionLine("POINTS", String.valueOf(pointsToUse), "积分抵扣", pointsDiscount));
        }

        BigDecimal totalDiscount = memberDiscount.add(promoDiscount).add(pointsDiscount);
        if (totalDiscount.compareTo(merchandise) > 0) totalDiscount = merchandise;
        List<CheckoutItem> items = allocateDiscount(raw, merchandise, totalDiscount);
        Map<Long, List<CheckoutItem>> byShop = items.stream().collect(Collectors.groupingBy(CheckoutItem::shopId, LinkedHashMap::new, Collectors.toList()));
        List<CheckoutShopGroup> groups = new ArrayList<>();
        for (var entry : byShop.entrySet()) {
            List<CheckoutItem> shopItems = entry.getValue();
            BigDecimal shopTotal = sum(shopItems, CheckoutItem::subtotal), shopDiscount = sum(shopItems, CheckoutItem::discountAmount);
            List<CheckoutWarehousePlan> plans = new ArrayList<>();
            for (CheckoutItem item : shopItems) {
                int remaining = item.quantity();
                List<WarehouseAvailability> warehouses = new ArrayList<>(stock.get(item.skuId()).warehouses());
                warehouses.sort(Comparator.comparing((WarehouseAvailability w) -> !w.regionCode().equals(address.regionCode())).thenComparing(WarehouseAvailability::priority, Comparator.reverseOrder()));
                for (WarehouseAvailability w : warehouses) {
                    if (remaining == 0) break;
                    int qty = Math.min(remaining, w.availableQty());
                    if (qty > 0) {
                        plans.add(new CheckoutWarehousePlan(w.warehouseId(), w.warehouseName(), item.skuId(), qty));
                        remaining -= qty;
                    }
                }
                if (remaining > 0) throw new BusinessException("TR40902", item.title() + " 无法完成分仓");
            }
            CheckoutItem first = shopItems.getFirst();
            groups.add(new CheckoutShopGroup(entry.getKey(), first.merchantId(), first.shopName(), shopTotal, shopDiscount, shopTotal.subtract(shopDiscount), shopItems, plans));
        }

        String discountType = promotionLines.isEmpty() ? "NONE" : promotionLines.getFirst().type();
        String discountSourceId = promotionLines.isEmpty() ? "NONE" : promotionLines.getFirst().sourceId();
        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        CheckoutPayload payload = new CheckoutPayload(token, expiresAt, address, merchandise, totalDiscount, BigDecimal.ZERO, merchandise.subtract(totalDiscount),
                discountType, discountSourceId, request.userCouponId(), pointsToUse, promotionLines, items, groups);
        try {
            String json = mapper.writeValueAsString(payload);
            String hash = sha256(mapper.writeValueAsString(request));
            jdbc.update("INSERT INTO trade_checkout_snapshot(id,checkout_token,user_id,request_hash,snapshot_json,status,expires_at,created_at) VALUES(?,?,?,?,?,'ACTIVE',?,CURRENT_TIMESTAMP)",
                    DistributedId.next(), token, userId, hash, json, java.sql.Timestamp.from(expiresAt));
            return payload;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private CheckoutAddress resolveAddress(long userId, CheckoutController.PreviewRequest request) {
        if (request.addressId() == null) {
            throw new BusinessException("TR40031", "结算必须选择地址簿中的收货地址");
        }
        Map<String, Object> addr = member.address(userId, request.addressId());
        return new CheckoutAddress(request.addressId(), String.valueOf(addr.get("receiver")), String.valueOf(addr.get("mobile")),
                String.valueOf(addr.get("regionCode")), String.valueOf(addr.get("detail")));
    }

    CheckoutPayload confirm(long userId, String token) {
        Map<String, Object> row = lookup(userId, token);
        if ("EXPIRED".equals(row.get("status")) || ((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now()))
            throw new BusinessException("TR40904", "结算凭证已过期，请重新结算");
        try {
            return mapper.readValue(String.valueOf(row.get("snapshot_json")), CheckoutPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> lookup(long userId, String token) {
        var rows = jdbc.queryForList("SELECT id,checkout_token,user_id,request_hash,snapshot_json,status,result_order_no,expires_at FROM trade_checkout_snapshot WHERE checkout_token=? AND user_id=?", token, userId);
        if (rows.isEmpty()) throw new BusinessException("TR40403", "结算凭证不存在");
        return rows.getFirst();
    }

    CheckoutPayload payload(String json) {
        try {
            return mapper.readValue(json, CheckoutPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    String hashOrderRequest(String token) {
        return sha256(token);
    }

    private static List<CheckoutItem> allocateDiscount(List<CheckoutItem> raw, BigDecimal total, BigDecimal discount) {
        List<CheckoutItem> result = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < raw.size(); i++) {
            CheckoutItem item = raw.get(i);
            BigDecimal share = i == raw.size() - 1 ? discount.subtract(allocated) : discount.multiply(item.subtotal()).divide(total, 2, RoundingMode.DOWN);
            allocated = allocated.add(share);
            result.add(new CheckoutItem(item.cartItemId(), item.spuId(), item.skuId(), item.merchantId(), item.shopId(), item.shopName(), item.title(), item.coverUrl(), item.skuCode(), item.unitPrice(), item.quantity(), item.subtotal(), share, item.subtotal().subtract(share)));
        }
        return result;
    }

    private static BigDecimal sum(List<CheckoutItem> items, Function<CheckoutItem, BigDecimal> getter) {
        return items.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long number(Object v) {
        return ((Number) v).longValue();
    }

    private static int integer(Object v) {
        return ((Number) v).intValue();
    }
}
