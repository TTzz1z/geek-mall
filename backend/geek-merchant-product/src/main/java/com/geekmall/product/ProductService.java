package com.geekmall.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import com.geekmall.common.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
class ProductService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProductService.class);
    private static final Set<String> DOCUMENT_CATEGORIES = Set.of("PRODUCT_SPEC");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DocumentStorageService storage;
    private final KnowledgeSyncNotifier knowledgeSync;

    ProductService(JdbcTemplate jdbc, ObjectMapper mapper, DocumentStorageService storage, KnowledgeSyncNotifier knowledgeSync) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.storage = storage;
        this.knowledgeSync = knowledgeSync;
    }

    List<Map<String, Object>> categories() {
        var rows = jdbc.queryForList("SELECT id,parent_id,category_name AS name,level,path FROM product_category WHERE status='ENABLED' ORDER BY sort_order,id");
        Map<Long, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (var row : rows) { row.put("children", new ArrayList<>()); nodes.put(number(row.get("id")), row); }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (var row : rows) {
            Object parent = row.get("parent_id");
            if (parent == null) roots.add(row); else ((List<Map<String,Object>>) nodes.get(number(parent)).get("children")).add(row);
        }
        return roots;
    }

    List<Map<String, Object>> brands(Long categoryId) {
        if (categoryId == null) return jdbc.queryForList("SELECT id,brand_name AS name,logo_url AS logoUrl,description FROM product_brand WHERE status='ENABLED' ORDER BY brand_name");
        return jdbc.queryForList("SELECT b.id,b.brand_name AS name,b.logo_url AS logoUrl,b.description FROM product_brand b JOIN product_category_brand cb ON cb.brand_id=b.id WHERE cb.category_id=? AND b.status='ENABLED'", categoryId);
    }

    List<Map<String,Object>> attributeTemplates(long categoryId) {
        var groups = jdbc.queryForList("SELECT id,group_name AS name,sort_order AS sortOrder FROM product_attr_group WHERE category_id=? ORDER BY sort_order,id", categoryId);
        for (var group : groups) {
            var attributes = jdbc.queryForList("SELECT id,attr_name AS name,attr_type AS type,unit,searchable,comparable,sale_attr AS saleAttribute FROM product_attr WHERE group_id=? ORDER BY id", group.get("id"));
            for (var attribute : attributes) attribute.put("options", jdbc.queryForList("SELECT id,option_value AS value,sort_order AS sortOrder FROM product_attr_option WHERE attr_id=? ORDER BY sort_order,id", attribute.get("id")));
            group.put("attributes", attributes);
        }
        return groups;
    }

    @Transactional
    long createCategory(ProductController.CategoryRequest body) {
        long id = DistributedId.next();
        int level = 1;
        String path = "/" + id;
        if (body.parentId() != null) {
            Map<String,Object> parent = one("SELECT level,path FROM product_category WHERE id=? AND status='ENABLED'", body.parentId());
            level = ((Number) parent.get("level")).intValue() + 1;
            path = parent.get("path") + "/" + id;
        }
        jdbc.update("INSERT INTO product_category(id,parent_id,category_name,level,path,sort_order,status) VALUES(?,?,?,?,?,?,'ENABLED')", id, body.parentId(), body.name(), level, path, body.sortOrder());
        return id;
    }

    @Transactional
    long createBrand(ProductController.BrandRequest body) {
        long id = DistributedId.next();
        jdbc.update("INSERT INTO product_brand(id,brand_name,logo_url,description,status) VALUES(?,?,?,?,'ENABLED')", id, body.name(), body.logoUrl(), body.description());
        if (body.categoryIds() != null) for (Long categoryId : new LinkedHashSet<>(body.categoryIds()))
            jdbc.update("INSERT INTO product_category_brand(category_id,brand_id) VALUES(?,?)", categoryId, id);
        return id;
    }

    long createAttributeGroup(long categoryId, ProductController.AttributeGroupRequest body) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM product_category WHERE id=? AND status='ENABLED'", Integer.class, categoryId) == 0) throw new BusinessException("PD40402", "分类不存在");
        long id = DistributedId.next();
        jdbc.update("INSERT INTO product_attr_group(id,category_id,group_name,sort_order) VALUES(?,?,?,?)", id, categoryId, body.name(), body.sortOrder());
        return id;
    }

    long createAttribute(long groupId, ProductController.AttributeRequest body) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM product_attr_group WHERE id=?", Integer.class, groupId) == 0) throw new BusinessException("PD40403", "属性组不存在");
        long id = DistributedId.next();
        jdbc.update("INSERT INTO product_attr(id,group_id,attr_name,attr_type,unit,searchable,comparable,sale_attr) VALUES(?,?,?,?,?,?,?,?)", id, groupId, body.name(), body.type(), body.unit(), body.searchable(), body.comparable(), body.saleAttribute());
        return id;
    }

    long createAttributeOption(long attributeId, ProductController.AttributeOptionRequest body) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM product_attr WHERE id=?", Integer.class, attributeId) == 0) throw new BusinessException("PD40404", "属性不存在");
        long id = DistributedId.next();
        jdbc.update("INSERT INTO product_attr_option(id,attr_id,option_value,sort_order) VALUES(?,?,?,?)", id, attributeId, body.value(), body.sortOrder());
        return id;
    }

    List<Map<String, Object>> catalog(Long categoryId) {
        String sql = """
                SELECT p.id,p.title,p.subtitle,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,
                       p.sales_count AS salesCount,p.rating,p.brand_id AS brandId,b.brand_name AS brandName,
                       p.shop_id AS shopId,s.shop_name AS shopName,s.shop_type AS shopType,p.source_type AS sourceType,
                       p.category_id AS categoryId,c.category_name AS categoryName
                FROM product_spu p
                JOIN product_brand b ON b.id=p.brand_id
                JOIN shop s ON s.id=p.shop_id
                JOIN product_category c ON c.id=p.category_id
                WHERE p.audit_status='APPROVED' AND p.sale_status='ON_SALE'
                """;
        List<Map<String, Object>> items;
        if (categoryId == null) {
            items = jdbc.queryForList(sql + " ORDER BY p.sales_count DESC LIMIT 48");
        } else {
            String path = jdbc.query("SELECT path FROM product_category WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, categoryId);
            if (path == null) throw new BusinessException("PD40402", "分类不存在");
            items = jdbc.queryForList(sql + " AND (c.id=? OR c.path LIKE ?) ORDER BY p.sales_count DESC LIMIT 48", categoryId, path + "%");
        }
        items.forEach(this::enrichSeller);
        return items;
    }

    List<Map<String, Object>> batchSpus(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        var unique = new LinkedHashSet<>(ids);
        String placeholders = String.join(",", Collections.nCopies(unique.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id,p.title,p.subtitle,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,
                       p.sales_count AS salesCount,p.rating,p.brand_id AS brandId,b.brand_name AS brandName,
                       p.shop_id AS shopId,s.shop_name AS shopName,s.shop_type AS shopType,p.source_type AS sourceType,
                       p.category_id AS categoryId,c.category_name AS categoryName
                FROM product_spu p
                JOIN product_brand b ON b.id=p.brand_id
                JOIN shop s ON s.id=p.shop_id
                JOIN product_category c ON c.id=p.category_id
                WHERE p.id IN (""" + placeholders + ") AND p.audit_status='APPROVED' AND p.sale_status='ON_SALE'",
                unique.toArray());
        Map<Long, Map<String, Object>> byId = new HashMap<>();
        for (var row : rows) {
            enrichSeller(row);
            byId.put(number(row.get("id")), row);
        }
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (Long id : unique) {
            Map<String, Object> hit = byId.get(id);
            if (hit != null) ordered.add(hit);
        }
        return ordered;
    }

    Map<String, Object> showcase() {
        List<Map<String, Object>> floors = new ArrayList<>();
        floors.add(floor("self_operated", "GEEK 自营精选", "平台自营采购的多品牌数码",
                "SELECT p.id,p.title,p.subtitle,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,p.sales_count AS salesCount,p.rating,p.brand_id AS brandId,b.brand_name AS brandName,p.shop_id AS shopId,s.shop_name AS shopName,s.shop_type AS shopType,p.source_type AS sourceType,p.category_id AS categoryId,c.category_name AS categoryName FROM product_spu p JOIN product_brand b ON b.id=p.brand_id JOIN shop s ON s.id=p.shop_id JOIN product_category c ON c.id=p.category_id WHERE p.audit_status='APPROVED' AND p.sale_status='ON_SALE' AND s.shop_type='SELF_OPERATED' ORDER BY p.sales_count DESC LIMIT 8"));
        floors.add(floor("geek_select", "GEEK SELECT 自有品牌", "充电、键鼠、扩展坞等配件",
                "SELECT p.id,p.title,p.subtitle,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,p.sales_count AS salesCount,p.rating,p.brand_id AS brandId,b.brand_name AS brandName,p.shop_id AS shopId,s.shop_name AS shopName,s.shop_type AS shopType,p.source_type AS sourceType,p.category_id AS categoryId,c.category_name AS categoryName FROM product_spu p JOIN product_brand b ON b.id=p.brand_id JOIN shop s ON s.id=p.shop_id JOIN product_category c ON c.id=p.category_id WHERE p.audit_status='APPROVED' AND p.sale_status='ON_SALE' AND b.brand_name='GEEK SELECT' ORDER BY p.sales_count DESC LIMIT 8"));
        floors.add(floor("flagship", "品牌旗舰店", "官方直营旗舰精选",
                "SELECT p.id,p.title,p.subtitle,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,p.sales_count AS salesCount,p.rating,p.brand_id AS brandId,b.brand_name AS brandName,p.shop_id AS shopId,s.shop_name AS shopName,s.shop_type AS shopType,p.source_type AS sourceType,p.category_id AS categoryId,c.category_name AS categoryName FROM product_spu p JOIN product_brand b ON b.id=p.brand_id JOIN shop s ON s.id=p.shop_id JOIN product_category c ON c.id=p.category_id WHERE p.audit_status='APPROVED' AND p.sale_status='ON_SALE' AND s.shop_type='OFFICIAL_FLAGSHIP' ORDER BY p.sales_count DESC LIMIT 8"));
        floors.add(floor("phones", "热门手机", "影像旗舰与折叠屏",
                "SELECT p.id,p.title,p.subtitle,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,p.sales_count AS salesCount,p.rating,p.brand_id AS brandId,b.brand_name AS brandName,p.shop_id AS shopId,s.shop_name AS shopName,s.shop_type AS shopType,p.source_type AS sourceType,p.category_id AS categoryId,c.category_name AS categoryName FROM product_spu p JOIN product_brand b ON b.id=p.brand_id JOIN shop s ON s.id=p.shop_id JOIN product_category c ON c.id=p.category_id WHERE p.audit_status='APPROVED' AND p.sale_status='ON_SALE' AND c.path LIKE '/200/%' ORDER BY p.sales_count DESC LIMIT 8"));
        floors.add(floor("computers", "电脑办公", "笔记本与专业显示",
                "SELECT p.id,p.title,p.subtitle,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,p.sales_count AS salesCount,p.rating,p.brand_id AS brandId,b.brand_name AS brandName,p.shop_id AS shopId,s.shop_name AS shopName,s.shop_type AS shopType,p.source_type AS sourceType,p.category_id AS categoryId,c.category_name AS categoryName FROM product_spu p JOIN product_brand b ON b.id=p.brand_id JOIN shop s ON s.id=p.shop_id JOIN product_category c ON c.id=p.category_id WHERE p.audit_status='APPROVED' AND p.sale_status='ON_SALE' AND (c.path LIKE '/100/%' OR c.path LIKE '/300/%') ORDER BY p.sales_count DESC LIMIT 8"));
        floors.add(floor("third_party", "第三方优选", "授权专营与优选外设",
                "SELECT p.id,p.title,p.subtitle,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,p.sales_count AS salesCount,p.rating,p.brand_id AS brandId,b.brand_name AS brandName,p.shop_id AS shopId,s.shop_name AS shopName,s.shop_type AS shopType,p.source_type AS sourceType,p.category_id AS categoryId,c.category_name AS categoryName FROM product_spu p JOIN product_brand b ON b.id=p.brand_id JOIN shop s ON s.id=p.shop_id JOIN product_category c ON c.id=p.category_id WHERE p.audit_status='APPROVED' AND p.sale_status='ON_SALE' AND s.shop_type='THIRD_PARTY' ORDER BY p.sales_count DESC LIMIT 8"));
        return Map.of("floors", floors, "hero", floors.isEmpty() || floors.get(0).get("items") == null || ((List<?>) floors.get(0).get("items")).isEmpty()
                ? Map.of()
                : ((List<Map<String, Object>>) floors.get(0).get("items")).get(0));
    }

    private Map<String, Object> floor(String code, String title, String subtitle, String sql) {
        List<Map<String, Object>> items = jdbc.queryForList(sql);
        items.forEach(this::enrichSeller);
        Map<String, Object> floor = new LinkedHashMap<>();
        floor.put("code", code);
        floor.put("title", title);
        floor.put("subtitle", subtitle);
        floor.put("items", items);
        return floor;
    }

    private void enrichSeller(Map<String, Object> product) {
        String shopType = String.valueOf(product.getOrDefault("shopType", ""));
        String brandName = String.valueOf(product.getOrDefault("brandName", ""));
        String sellerLabel = switch (shopType) {
            case "SELF_OPERATED" -> "平台自营";
            case "OFFICIAL_FLAGSHIP" -> "官方旗舰店";
            default -> "第三方店铺";
        };
        product.put("sellerLabel", sellerLabel);
        product.put("ownBrand", "GEEK SELECT".equalsIgnoreCase(brandName));
        product.put("deliveryHint", "SELF_OPERATED".equals(shopType) ? "次日达" : "多仓发货");
    }

    Map<String, Object> spu(long id, AuthPrincipal principal) {
        Map<String,Object> product = one("""
                SELECT p.id,p.merchant_id AS merchantId,p.shop_id AS shopId,p.title,p.subtitle,p.category_id AS categoryId,
                c.category_name AS categoryName,p.brand_id AS brandId,b.brand_name AS brandName,p.audit_status AS auditStatus,
                p.sale_status AS saleStatus,p.source_type AS sourceType,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,
                p.sales_count AS salesCount,p.rating,s.shop_name AS shopName,s.shop_type AS shopType,s.logo_url AS shopLogo,s.score AS shopScore,
                d.description_html AS descriptionHtml,d.package_list AS packageList,d.service_desc AS serviceDesc
                FROM product_spu p JOIN product_category c ON c.id=p.category_id JOIN product_brand b ON b.id=p.brand_id
                JOIN shop s ON s.id=p.shop_id LEFT JOIN product_spu_detail d ON d.spu_id=p.id WHERE p.id=?
                """, id);
        boolean publicVisible = "ON_SALE".equals(product.get("saleStatus"));
        boolean owner = principal != null && principal.merchantId() != null && principal.merchantId() == number(product.get("merchantId"));
        boolean admin = principal != null && (principal.hasRole("SYSTEM_ADMIN") || principal.hasRole("PRODUCT_ADMIN"));
        if (!publicVisible && !owner && !admin) throw new BusinessException("PD40401", "商品不存在或未上架");
        enrichSeller(product);
        product.put("skus", skus(id));
        product.put("attributes", jdbc.queryForList("SELECT a.id,a.attr_name AS name,COALESCE(v.value_text,CAST(v.value_num AS CHAR)) AS value,a.unit,a.comparable FROM product_spu_attr_value v JOIN product_attr a ON a.id=v.attr_id WHERE v.spu_id=? ORDER BY a.id", id));
        product.put("documents", jdbc.queryForList("SELECT id,doc_type AS type,file_url AS fileUrl,parse_status AS parseStatus,version FROM product_document WHERE spu_id=? ORDER BY created_at", id));
        if (principal != null && "USER".equals(principal.audience())) {
            jdbc.update("INSERT INTO member_browse_history(user_id,spu_id,viewed_at) VALUES(?,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE viewed_at=CURRENT_TIMESTAMP", principal.userId(), id);
        }
        return product;
    }

    List<Map<String,Object>> skus(long id) {
        var skus = jdbc.queryForList("""
                SELECT id,sku_code AS skuCode,barcode,sale_price AS salePrice,market_price AS marketPrice,
                       cost_price AS costPrice,weight,volume,status
                FROM product_sku WHERE spu_id=? ORDER BY sale_price,id
                """, id);
        for (var sku : skus) sku.put("saleAttributes", jdbc.queryForList("SELECT a.attr_name AS name,sa.value_text AS value,sa.attr_id AS attrId,sa.option_id AS optionId FROM product_sku_sale_attr sa JOIN product_attr a ON a.id=sa.attr_id WHERE sa.sku_id=? ORDER BY sa.attr_id", sku.get("id")));
        return skus;
    }

    List<Map<String,Object>> skuSnapshots(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) return List.of();
        var unique = new LinkedHashSet<>(skuIds);
        String placeholders = String.join(",", Collections.nCopies(unique.size(), "?"));
        return jdbc.queryForList("""
                SELECT k.id AS skuId,k.spu_id AS spuId,k.sku_code AS skuCode,k.sale_price AS salePrice,
                       k.market_price AS marketPrice,k.status AS skuStatus,p.title,p.subtitle,p.cover_url AS coverUrl,
                       p.merchant_id AS merchantId,p.shop_id AS shopId,p.audit_status AS auditStatus,
                       p.sale_status AS saleStatus,s.shop_name AS shopName
                FROM product_sku k JOIN product_spu p ON p.id=k.spu_id JOIN shop s ON s.id=p.shop_id
                WHERE k.id IN (""" + placeholders + ")", unique.toArray());
    }

    Map<String,Object> shop(long id) {
        Map<String,Object> shop = one("SELECT id,merchant_id AS merchantId,shop_name AS shopName,shop_type AS shopType,logo_url AS logoUrl,announcement,score,status,cert_status AS certStatus,cert_brand_id AS certBrandId FROM shop WHERE id=? AND status='ENABLED'", id);
        enrichSeller(shop);
        shop.put("sellerLabel", shop.get("sellerLabel"));
        List<Map<String,Object>> products = jdbc.queryForList("""
                SELECT p.id,p.title,p.subtitle,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,
                       p.sales_count AS salesCount,p.rating,p.brand_id AS brandId,b.brand_name AS brandName,
                       p.shop_id AS shopId,s.shop_name AS shopName,s.shop_type AS shopType,p.source_type AS sourceType
                FROM product_spu p JOIN product_brand b ON b.id=p.brand_id JOIN shop s ON s.id=p.shop_id
                WHERE p.shop_id=? AND p.audit_status='APPROVED' AND p.sale_status='ON_SALE'
                ORDER BY p.sales_count DESC
                """, id);
        products.forEach(this::enrichSeller);
        shop.put("products", products);
        return shop;
    }

    List<Map<String,Object>> merchantProducts(String status) {
        long merchantId = TenantContext.requireMerchantId();
        String sql = "SELECT id,title,subtitle,audit_status AS auditStatus,sale_status AS saleStatus,min_price AS minPrice,max_price AS maxPrice,cover_url AS coverUrl,version,updated_at AS updatedAt FROM product_spu WHERE merchant_id=?";
        if (status != null && !status.isBlank()) return jdbc.queryForList(sql + " AND sale_status=? ORDER BY updated_at DESC", merchantId, status);
        return jdbc.queryForList(sql + " ORDER BY updated_at DESC", merchantId);
    }

    @Transactional
    long createSpu(ProductController.ProductRequest body, AuthPrincipal principal, String requestId) {
        long merchantId = TenantContext.requireMerchantId();
        assertMerchantAuth(merchantId, body.categoryId(), body.brandId());
        long shopId = principal.shopId() != null ? principal.shopId() : jdbc.queryForObject("SELECT id FROM shop WHERE merchant_id=? AND status='ENABLED' ORDER BY id LIMIT 1", Long.class, merchantId);
        String shopType = jdbc.queryForObject("SELECT shop_type FROM shop WHERE id=?", String.class, shopId);
        String sourceType = "SELF_OPERATED".equals(shopType) ? "SELF_OPERATED" : "THIRD_PARTY";
        long id = DistributedId.next();
        jdbc.update("INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'DRAFT','OFF_SALE',0,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, merchantId, shopId, sourceType, body.categoryId(), body.brandId(), body.title(), body.subtitle(), body.coverUrl());
        jdbc.update("INSERT INTO product_spu_detail(spu_id,description_html,package_list,service_desc) VALUES(?,?,?,?)", id, body.descriptionHtml(), body.packageList(), body.serviceDesc());
        replaceProductChildren(id, body);
        log(id, "CREATED", principal.userId(), requestId);
        return id;
    }

    @Transactional
    void updateSpu(long id, ProductController.ProductRequest body, AuthPrincipal principal, String requestId) {
        updateSpu(id, body, principal, requestId, false);
    }

    @Transactional
    void updateSpu(long id, ProductController.ProductRequest body, AuthPrincipal principal, String requestId, boolean adminOverride) {
        if (adminOverride) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(1) FROM product_spu WHERE id=?", Integer.class, id);
            if (exists == null || exists == 0) throw new BusinessException("PD40401", "商品不存在");
            if (!isSelfOperated(id)) {
                throw new BusinessException("PD40330", "平台管理员仅可编辑自营商品；第三方商品请使用下架");
            }
        } else {
            owner(id);
            assertMerchantAuth(TenantContext.requireMerchantId(), body.categoryId(), body.brandId());
        }
        String audit = jdbc.queryForObject("SELECT audit_status FROM product_spu WHERE id=?", String.class, id);
        String sale = jdbc.queryForObject("SELECT sale_status FROM product_spu WHERE id=?", String.class, id);
        boolean draftLike = Set.of("DRAFT", "REJECTED").contains(audit);
        boolean approved = "APPROVED".equals(audit);
        if (!draftLike && !approved) {
            throw new BusinessException("PD40902", "审核中商品不可编辑，请先驳回或等待审核结束");
        }
        if ("VIOLATION_OFF_SALE".equals(sale) && !adminOverride) {
            throw new BusinessException("PD40905", "违规下架商品需平台解除后才能编辑");
        }

        jdbc.update("""
                UPDATE product_spu SET category_id=?,brand_id=?,title=?,subtitle=?,cover_url=?,
                updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=?
                """, body.categoryId(), body.brandId(), body.title(), body.subtitle(), body.coverUrl(), id);
        int detailUpdated = jdbc.update("""
                UPDATE product_spu_detail SET description_html=?,package_list=?,service_desc=? WHERE spu_id=?
                """, body.descriptionHtml(), body.packageList(), body.serviceDesc(), id);
        if (detailUpdated == 0) {
            jdbc.update("INSERT INTO product_spu_detail(spu_id,description_html,package_list,service_desc) VALUES(?,?,?,?)",
                    id, body.descriptionHtml(), body.packageList(), body.serviceDesc());
        }

        if (draftLike) {
            jdbc.update("DELETE FROM product_sku_sale_attr WHERE sku_id IN (SELECT id FROM product_sku WHERE spu_id=?)", id);
            jdbc.update("DELETE FROM product_sku WHERE spu_id=?", id);
            jdbc.update("DELETE FROM product_spu_attr_value WHERE spu_id=?", id);
            replaceProductChildren(id, body);
        } else {
            patchApprovedSkus(id, body);
        }

        log(id, "UPDATED", principal.userId(), requestId);
        if ("ON_SALE".equals(sale)) {
            event(id, "product.spu.on-sale.v1");
            knowledgeSync.sync(searchDocument(id), "UPSERT");
        }
    }

    /** Keep SKU IDs stable for on-sale products; update first SKU (or match by skuCode). */
    private void patchApprovedSkus(long spuId, ProductController.ProductRequest body) {
        if (body.skus() == null || body.skus().isEmpty()) {
            throw new BusinessException("PD40003", "至少需要一个 SKU");
        }
        var existing = jdbc.queryForList("SELECT id,sku_code AS skuCode FROM product_sku WHERE spu_id=? ORDER BY id", spuId);
        if (existing.isEmpty()) {
            replaceProductChildren(spuId, body);
            return;
        }
        var first = body.skus().get(0);
        Long targetId = number(existing.get(0).get("id"));
        for (var row : existing) {
            if (first.skuCode() != null && first.skuCode().equals(String.valueOf(row.get("skuCode")))) {
                targetId = number(row.get("id"));
                break;
            }
        }
        jdbc.update("""
                UPDATE product_sku SET sku_code=?,barcode=?,sale_price=?,market_price=?,cost_price=?,
                weight=?,volume=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND spu_id=?
                """, first.skuCode(), first.barcode(), first.salePrice(), first.marketPrice(), first.costPrice(),
                first.weight(), first.volume(), targetId, spuId);
        var prices = jdbc.queryForList("SELECT sale_price FROM product_sku WHERE spu_id=? AND status='ENABLED'", spuId);
        BigDecimal min = null, max = null;
        for (var p : prices) {
            BigDecimal price = (BigDecimal) p.get("sale_price");
            if (price == null) continue;
            min = min == null || price.compareTo(min) < 0 ? price : min;
            max = max == null || price.compareTo(max) > 0 ? price : max;
        }
        if (min != null) jdbc.update("UPDATE product_spu SET min_price=?,max_price=? WHERE id=?", min, max, spuId);
    }

    private void replaceProductChildren(long id, ProductController.ProductRequest body) {
        if (body.skus()==null || body.skus().isEmpty()) throw new BusinessException("PD40003","至少需要一个 SKU");
        BigDecimal min=null,max=null;
        for (var sku : body.skus()) {
            long skuId=DistributedId.next();
            jdbc.update("INSERT INTO product_sku(id,spu_id,sku_code,barcode,sale_price,market_price,cost_price,weight,volume,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",skuId,id,sku.skuCode(),sku.barcode(),sku.salePrice(),sku.marketPrice(),sku.costPrice(),sku.weight(),sku.volume());
            if(sku.attributes()!=null) for(var attr:sku.attributes()) jdbc.update("INSERT INTO product_sku_sale_attr(id,sku_id,attr_id,option_id,value_text) VALUES(?,?,?,?,?)",DistributedId.next(),skuId,attr.attrId(),attr.optionId(),attr.value());
            min=min==null||sku.salePrice().compareTo(min)<0?sku.salePrice():min; max=max==null||sku.salePrice().compareTo(max)>0?sku.salePrice():max;
        }
        if(body.attributes()!=null) for(var attr:body.attributes()) jdbc.update("INSERT INTO product_spu_attr_value(id,spu_id,attr_id,value_text,option_id) VALUES(?,?,?,?,?)",DistributedId.next(),id,attr.attrId(),attr.value(),attr.optionId());
        jdbc.update("UPDATE product_spu SET min_price=?,max_price=? WHERE id=?",min,max,id);
    }

    @Transactional
    void submit(long id, AuthPrincipal principal, String requestId) {
        owner(id);
        Map<String, Object> spu = one("SELECT merchant_id AS merchantId,category_id AS categoryId,brand_id AS brandId FROM product_spu WHERE id=?", id);
        assertMerchantAuth(number(spu.get("merchantId")), number(spu.get("categoryId")), number(spu.get("brandId")));
        transition(id, "audit_status", Set.of("DRAFT", "REJECTED"), "PENDING_REVIEW");
        log(id, "SUBMITTED", principal.userId(), requestId);
    }

    @Transactional
    void approve(long id, AuthPrincipal principal, String reason, String requestId) {
        Map<String, Object> spu = one("SELECT merchant_id AS merchantId,category_id AS categoryId,brand_id AS brandId FROM product_spu WHERE id=?", id);
        assertMerchantAuth(number(spu.get("merchantId")), number(spu.get("categoryId")), number(spu.get("brandId")));
        transition(id, "audit_status", Set.of("PENDING_REVIEW"), "APPROVED");
        audit(id, principal.userId(), "APPROVED", reason);
        log(id, "APPROVED", principal.userId(), requestId);
    }

    @Transactional
    void reject(long id, AuthPrincipal principal, String reason, String requestId) {
        transition(id, "audit_status", Set.of("PENDING_REVIEW"), "REJECTED");
        audit(id, principal.userId(), "REJECTED", reason);
        log(id, "REJECTED", principal.userId(), requestId);
    }

    @Transactional
    void onSale(long id, AuthPrincipal principal, String requestId) {
        owner(id);
        String audit = jdbc.queryForObject("SELECT audit_status FROM product_spu WHERE id=?", String.class, id);
        if (!"APPROVED".equals(audit)) throw new BusinessException("PD40904", "商品审核通过后才能上架");
        String sale = jdbc.queryForObject("SELECT sale_status FROM product_spu WHERE id=?", String.class, id);
        if ("VIOLATION_OFF_SALE".equals(sale)) throw new BusinessException("PD40905", "违规下架商品需平台解除后才能上架");
        transition(id, "sale_status", Set.of("OFF_SALE"), "ON_SALE");
        event(id, "product.spu.on-sale.v1");
        knowledgeSync.sync(searchDocument(id), "UPSERT");
        log(id, "ON_SALE", principal.userId(), requestId);
    }

    @Transactional
    void offSale(long id, AuthPrincipal principal, String requestId) {
        owner(id);
        transition(id, "sale_status", Set.of("ON_SALE"), "OFF_SALE");
        event(id, "product.spu.off-sale.v1");
        knowledgeSync.sync(searchDocument(id), "DEACTIVATE");
        log(id, "OFF_SALE", principal.userId(), requestId);
    }

    /** Platform force off-sale for any merchant's on-sale product. */
    @Transactional
    void adminForceOffSale(long id, AuthPrincipal principal, String requestId) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(1) FROM product_spu WHERE id=?", Integer.class, id);
        if (exists == null || exists == 0) throw new BusinessException("PD40401", "商品不存在");
        String sale = jdbc.queryForObject("SELECT sale_status FROM product_spu WHERE id=?", String.class, id);
        if (!"ON_SALE".equals(sale)) throw new BusinessException("PD40906", "仅在售商品可下架");
        transition(id, "sale_status", Set.of("ON_SALE"), "OFF_SALE");
        event(id, "product.spu.off-sale.v1");
        knowledgeSync.sync(searchDocument(id), "DEACTIVATE");
        log(id, "PLATFORM_OFF_SALE", principal.userId(), requestId);
    }

    /** Take down every currently on-sale SPU (platform emergency / batch). */
    @Transactional
    Map<String, Object> adminOffSaleAll(AuthPrincipal principal, String requestId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM product_spu WHERE sale_status='ON_SALE' ORDER BY id", Long.class);
        int count = 0;
        for (Long id : ids) {
            adminForceOffSale(id, principal, requestId);
            count++;
        }
        return Map.of("offSaleCount", count);
    }

    @Transactional
    void violationOffSale(long id, AuthPrincipal principal, String reason, String requestId) {
        if (reason == null || reason.isBlank()) throw new BusinessException("PD40010", "违规下架原因必填");
        String sale = jdbc.queryForObject("SELECT sale_status FROM product_spu WHERE id=?", String.class, id);
        transition(id, "sale_status", Set.of("ON_SALE", "OFF_SALE"), "VIOLATION_OFF_SALE");
        audit(id, principal.userId(), "VIOLATION_OFF_SALE", reason);
        if ("ON_SALE".equals(sale)) event(id, "product.spu.off-sale.v1");
        knowledgeSync.sync(searchDocument(id), "DEACTIVATE");
        log(id, "VIOLATION_OFF_SALE", principal.userId(), requestId);
    }

    @Transactional
    void clearViolation(long id, AuthPrincipal principal, String requestId) {
        transition(id, "sale_status", Set.of("VIOLATION_OFF_SALE"), "OFF_SALE");
        audit(id, principal.userId(), "CLEAR_VIOLATION", "平台解除违规下架");
        log(id, "CLEAR_VIOLATION", principal.userId(), requestId);
    }

    List<Map<String,Object>> pendingProducts() { return jdbc.queryForList("SELECT p.id,p.title,p.subtitle,p.merchant_id AS merchantId,p.shop_id AS shopId,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,p.audit_status AS auditStatus,p.sale_status AS saleStatus,p.source_type AS sourceType,p.updated_at AS submittedAt,s.shop_name AS shopName,s.shop_type AS shopType,b.brand_name AS brandName,c.category_name AS categoryName FROM product_spu p JOIN shop s ON s.id=p.shop_id JOIN product_brand b ON b.id=p.brand_id JOIN product_category c ON c.id=p.category_id WHERE p.audit_status='PENDING_REVIEW' ORDER BY p.updated_at"); }

    List<Map<String,Object>> adminProducts(String saleStatus) {
        String sql = """
                SELECT p.id,p.title,p.subtitle,p.merchant_id AS merchantId,p.shop_id AS shopId,
                       p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,
                       p.audit_status AS auditStatus,p.sale_status AS saleStatus,p.source_type AS sourceType,
                       p.updated_at AS updatedAt,
                       s.shop_name AS shopName,s.shop_type AS shopType,b.brand_name AS brandName,c.category_name AS categoryName
                FROM product_spu p
                JOIN shop s ON s.id=p.shop_id
                JOIN product_brand b ON b.id=p.brand_id
                JOIN product_category c ON c.id=p.category_id
                WHERE 1=1
                """;
        List<Map<String, Object>> rows;
        if (saleStatus != null && !saleStatus.isBlank()) {
            rows = jdbc.queryForList(sql + " AND p.sale_status=? ORDER BY p.updated_at DESC LIMIT 500", saleStatus);
        } else {
            rows = jdbc.queryForList(sql + " ORDER BY p.updated_at DESC LIMIT 500");
        }
        for (Map<String, Object> row : rows) {
            boolean self = "SELF_OPERATED".equals(String.valueOf(row.get("sourceType")))
                    || "SELF_OPERATED".equals(String.valueOf(row.get("shopType")));
            row.put("selfOperated", self);
            row.put("editableByAdmin", self);
        }
        return rows;
    }

    @Transactional
    Map<String,Object> uploadCover(long spuId, MultipartFile file, AuthPrincipal principal, String requestId, boolean adminOverride) {
        if (adminOverride) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(1) FROM product_spu WHERE id=?", Integer.class, spuId);
            if (exists == null || exists == 0) throw new BusinessException("PD40401", "商品不存在");
            if (!isSelfOperated(spuId)) {
                throw new BusinessException("PD40330", "平台管理员仅可编辑自营商品封面");
            }
        } else {
            owner(spuId);
        }
        DocumentStorageService.StoredObject stored = storage.uploadCover(spuId, file);
        String url = stored.reference();
        jdbc.update("UPDATE product_spu SET cover_url=?,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=?", url, spuId);
        log(spuId, "COVER_UPDATED", principal.userId(), requestId);
        uploadAudit(spuId, principal.userId(), "COVER", stored, requestId);
        String sale = jdbc.queryForObject("SELECT sale_status FROM product_spu WHERE id=?", String.class, spuId);
        if ("ON_SALE".equals(sale)) {
            event(spuId, "product.spu.on-sale.v1");
        }
        return Map.of("spuId", spuId, "coverUrl", url);
    }

    private boolean isSelfOperated(long spuId) {
        Map<String, Object> row = one("""
                SELECT p.source_type AS sourceType, s.shop_type AS shopType
                FROM product_spu p JOIN shop s ON s.id=p.shop_id WHERE p.id=?
                """, spuId);
        return "SELF_OPERATED".equals(String.valueOf(row.get("sourceType")))
                || "SELF_OPERATED".equals(String.valueOf(row.get("shopType")));
    }

    @Transactional
    long createApplication(ProductController.ApplicationRequest body, AuthPrincipal principal) {
        long id=DistributedId.next(); String no="MA"+Instant.now().toEpochMilli();
        jdbc.update("INSERT INTO merchant_application(id,application_no,applicant_user_id,company_name,credit_code,contact_name,contact_mobile,categories_json,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'DRAFT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,no,principal.userId(),body.companyName(),body.creditCode(),body.contactName(),body.contactMobile(),json(body.categories()));
        return id;
    }
    void submitApplication(long id, AuthPrincipal principal) { int changed=jdbc.update("UPDATE merchant_application SET status='SUBMITTED',updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=? AND applicant_user_id=? AND status IN ('DRAFT','REJECTED')",id,principal.userId()); if(changed==0)throw new BusinessException("MC40901","申请状态不可提交"); }
    List<Map<String,Object>> applications() { return jdbc.queryForList("SELECT id,application_no AS applicationNo,company_name AS companyName,credit_code AS creditCode,contact_name AS contactName,contact_mobile AS contactMobile,categories_json AS categories,status,reject_reason AS rejectReason,created_at AS createdAt FROM merchant_application ORDER BY created_at DESC"); }
    @Transactional
    void approveApplication(long id) {
        Map<String,Object> app=one("SELECT * FROM merchant_application WHERE id=? AND status='SUBMITTED'",id); long merchantId=DistributedId.next(); long shopId=DistributedId.next();
        jdbc.update("INSERT INTO merchant(id,merchant_type,company_name,credit_code,status,created_at,updated_at) VALUES(?,'THIRD_PARTY',?,?,'APPROVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",merchantId,app.get("company_name"),app.get("credit_code"));
        jdbc.update("INSERT INTO shop(id,merchant_id,shop_name,shop_type,status,created_at,updated_at) VALUES(?,?,?,'THIRD_PARTY','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",shopId,merchantId,app.get("company_name")+"旗舰店");
        jdbc.update("UPDATE merchant_application SET merchant_id=?,status='APPROVED',reject_reason=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",merchantId,id);
    }
    void rejectApplication(long id,String reason){ if(jdbc.update("UPDATE merchant_application SET status='REJECTED',reject_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='SUBMITTED'",reason,id)==0)throw new BusinessException("MC40902","申请状态不可驳回"); }

    Map<String,Object> merchantShop(){ return one("SELECT id,merchant_id AS merchantId,shop_name AS shopName,shop_type AS shopType,logo_url AS logoUrl,announcement,score,status,version FROM shop WHERE merchant_id=? ORDER BY id LIMIT 1",TenantContext.requireMerchantId()); }
    void updateShop(ProductController.ShopRequest body){ long merchantId=TenantContext.requireMerchantId(); jdbc.update("UPDATE shop SET shop_name=?,logo_url=?,announcement=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE merchant_id=?",body.shopName(),body.logoUrl(),body.announcement(),merchantId); }
    List<Map<String,Object>> subaccounts(){ return jdbc.queryForList("SELECT id,username,role_code AS roleCode,status,created_at AS createdAt FROM merchant_subaccount WHERE merchant_id=? ORDER BY created_at",TenantContext.requireMerchantId()); }
    long addSubaccount(ProductController.SubaccountRequest body,AuthPrincipal principal){ long id=DistributedId.next(); jdbc.update("INSERT INTO merchant_subaccount(id,merchant_id,shop_id,username,role_code,status,created_at) VALUES(?,?,?,?,?,'ENABLED',CURRENT_TIMESTAMP)",id,TenantContext.requireMerchantId(),principal.shopId(),body.username(),body.roleCode());return id; }

    long addDocument(long spuId, ProductController.DocumentRequest body){ owner(spuId);String type=documentCategory(body.type());if(!storage.isPrivateDocumentReference(body.fileUrl()))throw new BusinessException("PD40012","商品资料必须使用站内私有对象地址");long id=DistributedId.next();jdbc.update("INSERT INTO product_document(id,spu_id,doc_type,file_url,parse_status,created_at) VALUES(?,?,?,?,'PENDING',CURRENT_TIMESTAMP)",id,spuId,type,body.fileUrl());return id;}
    @Transactional
    Map<String,Object> uploadDocument(long spuId,String type,MultipartFile file,AuthPrincipal principal,String requestId){owner(spuId);String category=documentCategory(type);DocumentStorageService.StoredObject stored=storage.uploadDocument(spuId,file);long id=DistributedId.next();jdbc.update("INSERT INTO product_document(id,spu_id,doc_type,file_url,parse_status,created_at) VALUES(?,?,?,?,'UPLOADED',CURRENT_TIMESTAMP)",id,spuId,category,stored.reference());uploadAudit(spuId,principal.userId(),"DOCUMENT",stored,requestId);return Map.of("id",id,"fileUrl",stored.reference(),"fileName",stored.fileName(),"contentType",stored.contentType(),"size",stored.size());}
    DocumentStorageService.DownloadedObject downloadDocument(long spuId,long documentId){owner(spuId);String reference=jdbc.query("SELECT file_url FROM product_document WHERE id=? AND spu_id=?",rs->rs.next()?rs.getString(1):null,documentId,spuId);if(reference==null)throw new BusinessException("PD40409","商品资料不存在");return storage.downloadDocument(reference);}
    List<Map<String,Object>> favorites(long userId){return jdbc.queryForList("SELECT p.id,p.title,p.subtitle,p.min_price AS minPrice,p.cover_url AS coverUrl,p.rating,f.created_at AS createdAt FROM member_favorite_product f JOIN product_spu p ON p.id=f.spu_id WHERE f.user_id=? AND p.sale_status='ON_SALE' ORDER BY f.created_at DESC",userId);}
    void favorite(long userId,long spuId){spu(spuId,null);jdbc.update("INSERT IGNORE INTO member_favorite_product(user_id,spu_id,created_at) VALUES(?,?,CURRENT_TIMESTAMP)",userId,spuId);}
    void unfavorite(long userId,long spuId){jdbc.update("DELETE FROM member_favorite_product WHERE user_id=? AND spu_id=?",userId,spuId);}
    List<Map<String,Object>> follows(long userId){return jdbc.queryForList("SELECT s.id,s.shop_name AS shopName,s.logo_url AS logoUrl,s.score,f.created_at AS createdAt FROM member_follow_shop f JOIN shop s ON s.id=f.shop_id WHERE f.user_id=? ORDER BY f.created_at DESC",userId);}
    void follow(long userId,long shopId){shop(shopId);jdbc.update("INSERT IGNORE INTO member_follow_shop(user_id,shop_id,created_at) VALUES(?,?,CURRENT_TIMESTAMP)",userId,shopId);}
    void unfollow(long userId,long shopId){jdbc.update("DELETE FROM member_follow_shop WHERE user_id=? AND shop_id=?",userId,shopId);}
    List<Map<String,Object>> history(long userId){return jdbc.queryForList("SELECT p.id,p.title,p.min_price AS minPrice,p.cover_url AS coverUrl,h.viewed_at AS viewedAt FROM member_browse_history h JOIN product_spu p ON p.id=h.spu_id WHERE h.user_id=? ORDER BY h.viewed_at DESC LIMIT 100",userId);}

    @Transactional
    int reindexAll(){var ids=jdbc.queryForList("SELECT id FROM product_spu WHERE audit_status='APPROVED' AND sale_status='ON_SALE'",Long.class);ids.forEach(id->event(id,"product.spu.on-sale.v1"));return ids.size();}
    int retryOutbox(){return jdbc.update("UPDATE event_outbox SET status='PENDING',next_retry_at=CURRENT_TIMESTAMP WHERE status='FAILED'");}
    List<Map<String,Object>> allSearchDocuments(){var ids=jdbc.queryForList("SELECT id FROM product_spu WHERE audit_status='APPROVED' AND sale_status='ON_SALE'",Long.class);return ids.stream().map(this::searchDocument).toList();}

    private void owner(long id){Long merchant=jdbc.query("SELECT merchant_id FROM product_spu WHERE id=?",rs->rs.next()?rs.getLong(1):null,id);if(merchant==null)throw new BusinessException("PD40401","商品不存在");TenantContext.assertAccessible(merchant);}

    void assertMerchantAuth(long merchantId, long categoryId, long brandId) {
        Integer cat = jdbc.queryForObject("""
                SELECT COUNT(1) FROM merchant_category_auth
                WHERE merchant_id=? AND category_id=? AND status='ENABLED'
                  AND (valid_until IS NULL OR valid_until >= CURRENT_DATE)
                """, Integer.class, merchantId, categoryId);
        if (cat == null || cat == 0) throw new BusinessException("PD40320", "商家未获得该类目授权");
        Integer brand = jdbc.queryForObject("""
                SELECT COUNT(1) FROM merchant_brand_auth
                WHERE merchant_id=? AND brand_id=? AND status='ENABLED'
                  AND (valid_until IS NULL OR valid_until >= CURRENT_DATE)
                """, Integer.class, merchantId, brandId);
        if (brand == null || brand == 0) throw new BusinessException("PD40321", "商家未获得该品牌授权");
    }

    List<Map<String, Object>> listCategoryAuths(long merchantId) {
        return jdbc.queryForList("""
                SELECT a.id,a.merchant_id AS merchantId,a.category_id AS categoryId,c.category_name AS categoryName,
                       a.status,a.valid_until AS validUntil,a.created_at AS createdAt
                FROM merchant_category_auth a JOIN product_category c ON c.id=a.category_id
                WHERE a.merchant_id=? ORDER BY a.category_id
                """, merchantId);
    }

    List<Map<String, Object>> listBrandAuths(long merchantId) {
        return jdbc.queryForList("""
                SELECT a.id,a.merchant_id AS merchantId,a.brand_id AS brandId,b.brand_name AS brandName,
                       a.file_url AS fileUrl,a.status,a.valid_until AS validUntil,a.created_at AS createdAt
                FROM merchant_brand_auth a JOIN product_brand b ON b.id=a.brand_id
                WHERE a.merchant_id=? ORDER BY a.brand_id
                """, merchantId);
    }

    @Transactional
    long grantCategoryAuth(long merchantId, long categoryId, java.sql.Date validUntil) {
        Long existing = jdbc.query("SELECT id FROM merchant_category_auth WHERE merchant_id=? AND category_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, merchantId, categoryId);
        if (existing != null) {
            jdbc.update("UPDATE merchant_category_auth SET status='ENABLED',valid_until=? WHERE id=?", validUntil, existing);
            return existing;
        }
        long id = DistributedId.next();
        jdbc.update("INSERT INTO merchant_category_auth(id,merchant_id,category_id,status,valid_until,created_at) VALUES(?,?,?,'ENABLED',?,CURRENT_TIMESTAMP)",
                id, merchantId, categoryId, validUntil);
        return id;
    }

    @Transactional
    long grantBrandAuth(long merchantId, long brandId, String fileUrl, java.sql.Date validUntil) {
        Long existing = jdbc.query("SELECT id FROM merchant_brand_auth WHERE merchant_id=? AND brand_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, merchantId, brandId);
        if (existing != null) {
            jdbc.update("UPDATE merchant_brand_auth SET status='ENABLED',file_url=?,valid_until=? WHERE id=?", fileUrl, validUntil, existing);
            return existing;
        }
        long id = DistributedId.next();
        jdbc.update("INSERT INTO merchant_brand_auth(id,merchant_id,brand_id,file_url,status,valid_until,created_at) VALUES(?,?,?,?,'ENABLED',?,CURRENT_TIMESTAMP)",
                id, merchantId, brandId, fileUrl, validUntil);
        return id;
    }

    void revokeCategoryAuth(long id) {
        if (jdbc.update("UPDATE merchant_category_auth SET status='DISABLED' WHERE id=?", id) == 0) {
            throw new BusinessException("PD40420", "类目授权不存在");
        }
    }

    void revokeBrandAuth(long id) {
        if (jdbc.update("UPDATE merchant_brand_auth SET status='DISABLED' WHERE id=?", id) == 0) {
            throw new BusinessException("PD40421", "品牌授权不存在");
        }
    }

    List<Map<String, Object>> listFlagshipCerts(String status) {
        String sql = """
                SELECT c.id,c.shop_id AS shopId,s.shop_name AS shopName,c.merchant_id AS merchantId,c.brand_id AS brandId,
                       b.brand_name AS brandName,c.evidence_url AS evidenceUrl,c.status,c.reject_reason AS rejectReason,
                       c.created_at AS createdAt,c.updated_at AS updatedAt
                FROM shop_flagship_cert c
                JOIN shop s ON s.id=c.shop_id
                JOIN product_brand b ON b.id=c.brand_id
                """;
        if (status != null && !status.isBlank()) {
            return jdbc.queryForList(sql + " WHERE c.status=? ORDER BY c.created_at DESC", status);
        }
        return jdbc.queryForList(sql + " ORDER BY c.created_at DESC");
    }

    @Transactional
    long applyFlagshipCert(AuthPrincipal principal, long brandId, String evidenceUrl) {
        long merchantId = TenantContext.requireMerchantId();
        long shopId = principal.shopId() != null ? principal.shopId()
                : jdbc.queryForObject("SELECT id FROM shop WHERE merchant_id=? ORDER BY id LIMIT 1", Long.class, merchantId);
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(1) FROM shop_flagship_cert WHERE shop_id=? AND status='PENDING'", Integer.class, shopId);
        if (pending != null && pending > 0) throw new BusinessException("PD40930", "已有待审旗舰认证申请");
        long id = DistributedId.next();
        jdbc.update("""
                INSERT INTO shop_flagship_cert(id,shop_id,merchant_id,brand_id,evidence_url,status,created_at,updated_at)
                VALUES(?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, shopId, merchantId, brandId, evidenceUrl);
        return id;
    }

    @Transactional
    void approveFlagshipCert(long id, AuthPrincipal principal) {
        Map<String, Object> cert = one("SELECT * FROM shop_flagship_cert WHERE id=? AND status='PENDING'", id);
        long shopId = number(cert.get("shop_id"));
        long brandId = number(cert.get("brand_id"));
        jdbc.update("""
                UPDATE shop_flagship_cert SET status='APPROVED',auditor_id=?,reject_reason=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, principal.userId(), id);
        jdbc.update("""
                UPDATE shop SET shop_type='OFFICIAL_FLAGSHIP',cert_status='APPROVED',cert_brand_id=?,
                       version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, brandId, shopId);
    }

    @Transactional
    void rejectFlagshipCert(long id, AuthPrincipal principal, String reason) {
        if (reason == null || reason.isBlank()) throw new BusinessException("PD40011", "驳回原因必填");
        if (jdbc.update("""
                UPDATE shop_flagship_cert SET status='REJECTED',auditor_id=?,reject_reason=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='PENDING'
                """, principal.userId(), reason, id) == 0) {
            throw new BusinessException("PD40422", "可驳回的认证申请不存在");
        }
    }

    private void transition(long id,String field,Set<String> from,String to){String current=jdbc.queryForObject("SELECT "+field+" FROM product_spu WHERE id=?",String.class,id);if("audit_status".equals(field))ProductStateMachine.audit(current,to);else ProductStateMachine.sale(current,to);jdbc.update("UPDATE product_spu SET "+field+"=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",to,id);}
    private void audit(long id,long userId,String result,String reason){jdbc.update("INSERT INTO product_audit_record(id,spu_id,auditor_id,result,reason,created_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",DistributedId.next(),id,userId,result,reason);}
    private void log(long id,String action,long userId,String requestId){jdbc.update("INSERT INTO product_operation_log(id,spu_id,action,operator_id,request_id,created_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",DistributedId.next(),id,action,userId,requestId);}
    private static String documentCategory(String value){String category=value==null?"":value.strip().toUpperCase(Locale.ROOT);if(!DOCUMENT_CATEGORIES.contains(category))throw new BusinessException("PD40013","商品资料类别仅支持 PRODUCT_SPEC");return category;}
    private static void uploadAudit(long spuId,long uploaderId,String uploadKind,DocumentStorageService.StoredObject stored,String requestId){LOGGER.info("product_file_upload spu_id={} uploader_id={} upload_kind={} file_type={} file_size={} object_path={} request_id={}",spuId,uploaderId,uploadKind,stored.contentType(),stored.size(),stored.objectPath(),requestId);}
    private void event(long id,String type){String eventId=UUID.randomUUID().toString();Map<String,Object> product=searchDocument(id);Map<String,Object> envelope=Map.of("eventId",eventId,"eventType",type,"occurredAt",Instant.now().toString(),"producer","geek-merchant-product","aggregateId",String.valueOf(id),"version",1,"payload",product);jdbc.update("INSERT INTO event_outbox(id,event_id,aggregate_type,aggregate_id,event_type,payload_json,status,created_at) VALUES(?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP)",DistributedId.next(),eventId,"PRODUCT_SPU",String.valueOf(id),type,json(envelope));}
    Map<String,Object> searchDocument(long id){
        Map<String,Object>d=one("SELECT p.id,p.title,p.subtitle,p.category_id AS categoryId,c.category_name AS categoryName,p.brand_id AS brandId,b.brand_name AS brandName,p.shop_id AS shopId,s.shop_name AS shopName,s.shop_type AS shopType,p.min_price AS minPrice,p.max_price AS maxPrice,p.cover_url AS coverUrl,p.sales_count AS salesCount,p.rating,p.source_type AS sourceType,p.audit_status AS auditStatus,p.sale_status AS saleStatus FROM product_spu p JOIN product_category c ON c.id=p.category_id JOIN product_brand b ON b.id=p.brand_id JOIN shop s ON s.id=p.shop_id WHERE p.id=?",id);
        enrichSeller(d);
        d.put("attributes",jdbc.queryForList("SELECT a.attr_name AS name,v.value_text AS value,a.searchable FROM product_spu_attr_value v JOIN product_attr a ON a.id=v.attr_id WHERE v.spu_id=?",id));
        return d;
    }
    private Map<String,Object> one(String sql,Object...args){var list=jdbc.queryForList(sql,args);if(list.isEmpty())throw new BusinessException("PD40401","数据不存在");return new LinkedHashMap<>(list.getFirst());}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private static long number(Object value){return ((Number)value).longValue();}
}
