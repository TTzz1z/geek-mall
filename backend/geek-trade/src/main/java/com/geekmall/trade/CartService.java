package com.geekmall.trade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class CartService {
    private final JdbcTemplate jdbc; private final ProductSnapshotClient products; private final InventoryClient inventory; private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    CartService(JdbcTemplate jdbc,ProductSnapshotClient products,InventoryClient inventory,ObjectMapper mapper){this.jdbc=jdbc;this.products=products;this.inventory=inventory;this.mapper=mapper;this.transactions=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));}

    void add(long userId,CartController.AddItem body){
        SkuSnapshot sku=requireSaleable(body.skuId());
        int available=available(sku,body.quantity());
        addPrepared(userId,body,sku,available);
    }

    private int available(SkuSnapshot sku,int quantity) {
        return inventory.availability(List.of(Map.of("merchantId",sku.merchantId(),"skuId",sku.skuId(),"quantity",quantity))).getFirst().availableQty();
    }

    private void addPrepared(long userId,CartController.AddItem body,SkuSnapshot sku,int available) {
        if(body.quantity()>available)throw new BusinessException("TR40902","加入数量超过可售库存");
        try{
            jdbc.update("""
                INSERT INTO trade_cart_item(id,user_id,shop_id,merchant_id,spu_id,sku_id,quantity,selected,added_price,title_snapshot,sku_snapshot,cover_url,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,TRUE,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE quantity=LEAST(quantity+VALUES(quantity),?),selected=TRUE,updated_at=CURRENT_TIMESTAMP
                """,DistributedId.next(),userId,sku.shopId(),sku.merchantId(),sku.spuId(),sku.skuId(),body.quantity(),sku.salePrice(),sku.title(),mapper.writeValueAsString(sku),sku.coverUrl(),available);
        }catch(JsonProcessingException e){throw new IllegalStateException(e);}
    }

    Map<String, Object> addIdempotent(long userId, CartController.AddItem body, String key) {
        if (key == null || key.isBlank()) {
            add(userId, body);
            return cart(userId);
        }
        if (key.length() > 100 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new BusinessException("TR40002", "幂等键格式无效");
        }
        var existing = jdbc.queryForList("SELECT * FROM trade_cart_mutation WHERE user_id=? AND step_key=?",userId,key);
        if (existing.isEmpty()) {
            // Fetch live business data before opening the transaction or taking the mutation lock.
            SkuSnapshot sku = requireSaleable(body.skuId());
            int stock = available(sku,body.quantity());
            transactions.executeWithoutResult(status -> {
                try {
                    jdbc.update("INSERT INTO trade_cart_mutation(user_id,step_key,sku_id,quantity) VALUES(?,?,?,?)",
                            userId,key,body.skuId(),body.quantity());
                } catch (DuplicateKeyException replay) {
                    // A committed marker proves that the cart write in that transaction also committed.
                    return;
                }
                addPrepared(userId,body,sku,stock);
            });
        }
        var row = jdbc.queryForMap("SELECT * FROM trade_cart_mutation WHERE user_id=? AND step_key=?",userId,key);
        if (((Number) row.get("sku_id")).longValue() != body.skuId()
                || ((Number) row.get("quantity")).intValue() != body.quantity()) {
            throw new BusinessException("TR40903", "幂等键已用于不同加购参数");
        }
        try {
            if (row.get("result_json") != null) {
                return mapper.readValue(String.valueOf(row.get("result_json")),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
            // Rendering may call other services. If it fails, replay resumes here without adding again.
            var result = cart(userId);
            jdbc.update("UPDATE trade_cart_mutation SET result_json=? WHERE user_id=? AND step_key=? AND result_json IS NULL",
                    mapper.writeValueAsString(result), userId, key);
            String stored = jdbc.queryForObject("SELECT result_json FROM trade_cart_mutation WHERE user_id=? AND step_key=?",
                    String.class,userId,key);
            return mapper.readValue(stored,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    void update(long userId,long id,CartController.UpdateItem body){
        if(body.quantity()==null&&body.selected()==null)throw new BusinessException("TR40002","数量和选中状态至少提供一个");
        if(body.quantity()!=null){Map<String,Object> row=own(userId,id);long merchant=((Number)row.get("merchant_id")).longValue(),sku=((Number)row.get("sku_id")).longValue();int available=inventory.availability(List.of(Map.of("merchantId",merchant,"skuId",sku,"quantity",body.quantity()))).getFirst().availableQty();if(body.quantity()>available)throw new BusinessException("TR40902","修改数量超过可售库存");jdbc.update("UPDATE trade_cart_item SET quantity=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",body.quantity(),id,userId);}
        if(body.selected()!=null)jdbc.update("UPDATE trade_cart_item SET selected=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",body.selected(),id,userId);
    }
    void delete(long userId,long id){if(jdbc.update("DELETE FROM trade_cart_item WHERE id=? AND user_id=?",id,userId)==0)throw new BusinessException("TR40401","购物车项不存在");}

    Map<String,Object> cart(long userId){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,shop_id,merchant_id,spu_id,sku_id,quantity,selected,added_price,title_snapshot,cover_url,updated_at FROM trade_cart_item WHERE user_id=? ORDER BY shop_id,updated_at DESC",userId);
        if(rows.isEmpty())return Map.of("shops",List.of(),"itemCount",0,"selectedCount",0,"selectedAmount",BigDecimal.ZERO);
        List<Long> skuIds=rows.stream().map(r->((Number)r.get("sku_id")).longValue()).toList();
        Map<Long,SkuSnapshot> current=products.snapshots(skuIds).stream().collect(Collectors.toMap(SkuSnapshot::skuId,Function.identity()));
        List<Map<String,Object>> request=rows.stream().map(r->Map.<String,Object>of("merchantId",((Number)r.get("merchant_id")).longValue(),"skuId",((Number)r.get("sku_id")).longValue(),"quantity",((Number)r.get("quantity")).intValue())).toList();
        Map<Long,StockAvailability> stocks=inventory.availability(request).stream().collect(Collectors.toMap(StockAvailability::skuId,Function.identity()));
        Map<Long,List<Map<String,Object>>> byShop=new LinkedHashMap<>();BigDecimal amount=BigDecimal.ZERO;int selected=0;
        for(Map<String,Object> row:rows){
            long skuId=((Number)row.get("sku_id")).longValue();
            long spuId=((Number)row.get("spu_id")).longValue();
            SkuSnapshot sku=current.get(skuId);
            StockAvailability stock=stocks.get(skuId);
            BigDecimal added=(BigDecimal)row.get("added_price");
            int quantity=((Number)row.get("quantity")).intValue();
            boolean checked=Boolean.TRUE.equals(row.get("selected"));
            boolean saleable=sku!=null&&"ON_SALE".equals(sku.saleStatus())&&"ENABLED".equals(sku.skuStatus());
            BigDecimal price=sku==null?added:sku.salePrice();
            boolean enough=stock!=null&&stock.availableQty()>=quantity;
            String title=sku!=null&&sku.title()!=null&&!sku.title().isBlank()?sku.title():String.valueOf(row.get("title_snapshot"));
            String cover=preferCover(spuId, sku==null?null:sku.coverUrl(), row.get("cover_url"));
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",row.get("id"));
            item.put("shopId",row.get("shop_id"));
            item.put("merchantId",row.get("merchant_id"));
            item.put("spuId",spuId);
            item.put("skuId",skuId);
            item.put("quantity",quantity);
            item.put("selected",checked);
            item.put("addedPrice",added);
            item.put("titleSnapshot",title);
            item.put("coverUrl",cover);
            item.put("currentPrice",price);
            item.put("priceChanged",price.compareTo(added)!=0);
            item.put("availableQty",stock==null?0:stock.availableQty());
            item.put("saleable",saleable);
            item.put("stockEnough",enough);
            item.put("invalidReason",!saleable?"商品已下架":!enough?"库存不足":null);
            item.put("lineAmount",price.multiply(BigDecimal.valueOf(quantity)));
            if(checked&&saleable&&enough){selected++;amount=amount.add((BigDecimal)item.get("lineAmount"));}
            byShop.computeIfAbsent(((Number)row.get("shop_id")).longValue(),ignored->new ArrayList<>()).add(item);
        }
        List<Map<String,Object>> shops=new ArrayList<>();for(var entry:byShop.entrySet()){SkuSnapshot first=current.get(((Number)entry.getValue().getFirst().get("skuId")).longValue());shops.add(Map.of("shopId",entry.getKey(),"shopName",first==null?"商品店铺":first.shopName(),"items",entry.getValue()));}
        return Map.of("shops",shops,"itemCount",rows.size(),"selectedCount",selected,"selectedAmount",amount);
    }

    List<Map<String,Object>> selectedRows(long userId,List<Long> ids){String extra="";Object[] args;if(ids!=null&&!ids.isEmpty()){extra=" AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";List<Object>a=new ArrayList<>();a.add(userId);a.addAll(ids);args=a.toArray();}else args=new Object[]{userId};return jdbc.queryForList("SELECT id,shop_id,merchant_id,spu_id,sku_id,quantity,added_price FROM trade_cart_item WHERE user_id=? AND selected=TRUE"+extra+" ORDER BY shop_id,id",args);}
    private SkuSnapshot requireSaleable(long skuId){return products.snapshots(List.of(skuId)).stream().filter(s->"ON_SALE".equals(s.saleStatus())&&"ENABLED".equals(s.skuStatus())).findFirst().orElseThrow(()->new BusinessException("TR40402","SKU 不存在或不可售"));}
    private Map<String,Object> own(long userId,long id){var rows=jdbc.queryForList("SELECT id,merchant_id,sku_id FROM trade_cart_item WHERE id=? AND user_id=?",id,userId);if(rows.isEmpty())throw new BusinessException("TR40401","购物车项不存在");return rows.getFirst();}

    private static String preferCover(long spuId, String liveCover, Object snapshotCover) {
        String local = "http://127.0.0.1:8088/covers/geek-spu-" + spuId + ".jpg";
        if (spuId > 0 && spuId < 100000) return local;
        String live = liveCover == null ? "" : liveCover.trim();
        if (!live.isBlank() && !isFragileCover(live)) return live;
        String snap = snapshotCover == null ? "" : String.valueOf(snapshotCover).trim();
        if (!snap.isBlank() && !isFragileCover(snap)) return snap;
        return live.isBlank() ? snap : live;
    }

    private static boolean isFragileCover(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("picsum.photos") || u.contains("unsplash.com");
    }
}
