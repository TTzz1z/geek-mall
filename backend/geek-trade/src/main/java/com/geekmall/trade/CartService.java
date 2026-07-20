package com.geekmall.trade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class CartService {
    private final JdbcTemplate jdbc; private final ProductSnapshotClient products; private final InventoryClient inventory; private final ObjectMapper mapper;
    CartService(JdbcTemplate jdbc,ProductSnapshotClient products,InventoryClient inventory,ObjectMapper mapper){this.jdbc=jdbc;this.products=products;this.inventory=inventory;this.mapper=mapper;}

    @Transactional
    void add(long userId,CartController.AddItem body){
        SkuSnapshot sku=requireSaleable(body.skuId());
        int available=inventory.availability(List.of(Map.of("merchantId",sku.merchantId(),"skuId",sku.skuId(),"quantity",body.quantity()))).getFirst().availableQty();
        if(body.quantity()>available)throw new BusinessException("TR40902","加入数量超过可售库存");
        try{
            jdbc.update("""
                INSERT INTO trade_cart_item(id,user_id,shop_id,merchant_id,spu_id,sku_id,quantity,selected,added_price,title_snapshot,sku_snapshot,cover_url,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,TRUE,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE quantity=LEAST(quantity+VALUES(quantity),?),selected=TRUE,updated_at=CURRENT_TIMESTAMP
                """,DistributedId.next(),userId,sku.shopId(),sku.merchantId(),sku.spuId(),sku.skuId(),body.quantity(),sku.salePrice(),sku.title(),mapper.writeValueAsString(sku),sku.coverUrl(),available);
        }catch(JsonProcessingException e){throw new IllegalStateException(e);}
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
