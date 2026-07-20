package com.geekmall.product;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
class ProductController {
    private final ProductService service;
    private final String internalKey;
    ProductController(ProductService service,@Value("${geek.internal-key}")String internalKey){this.service=service;this.internalKey=internalKey;}
    @GetMapping("/products/categories/tree") ApiResponse<List<Map<String,Object>>> categories(HttpServletRequest r){return ok(service.categories(),r);}
    @GetMapping("/products/brands") ApiResponse<List<Map<String,Object>>> brands(@RequestParam(required=false)Long categoryId,HttpServletRequest r){return ok(service.brands(categoryId),r);}
    @GetMapping("/products/categories/{categoryId}/attributes") ApiResponse<List<Map<String,Object>>> attributes(@PathVariable long categoryId,HttpServletRequest r){return ok(service.attributeTemplates(categoryId),r);}
    @GetMapping("/products/showcase") ApiResponse<Map<String,Object>> showcase(HttpServletRequest r){return ok(service.showcase(),r);}
    @GetMapping("/products/catalog") ApiResponse<List<Map<String,Object>>> catalog(@RequestParam(required=false)Long categoryId,HttpServletRequest r){return ok(service.catalog(categoryId),r);}
    @GetMapping("/products/batch") ApiResponse<List<Map<String,Object>>> batch(@RequestParam String ids,HttpServletRequest r){
        List<Long> list=new java.util.ArrayList<>();
        for(String p:ids.split(",")){ if(p!=null&&!p.isBlank()) list.add(Long.parseLong(p.trim())); }
        return ok(service.batchSpus(list),r);
    }
    @GetMapping("/products/spus/{id}") ApiResponse<Map<String,Object>> spu(@PathVariable long id,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.spu(id,p),r);}
    @GetMapping("/products/spus/{id}/skus") ApiResponse<List<Map<String,Object>>> skus(@PathVariable long id,HttpServletRequest r){return ok(service.skus(id),r);}
    @GetMapping("/shops/{id}") ApiResponse<Map<String,Object>> shop(@PathVariable long id,HttpServletRequest r){return ok(service.shop(id),r);}

    @GetMapping("/merchant/products/spus") @PreAuthorize("hasAnyRole('MERCHANT','PRODUCT_ADMIN','OPS')") ApiResponse<List<Map<String,Object>>> merchantProducts(@RequestParam(required=false)String status,HttpServletRequest r){return ok(service.merchantProducts(status),r);}
    @PostMapping("/merchant/products/spus") @PreAuthorize("hasAnyRole('MERCHANT','PRODUCT_ADMIN','OPS')") ApiResponse<Map<String,Long>> create(@Valid @RequestBody ProductRequest b,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(Map.of("id",service.createSpu(b,p,id(r))),r);}
    @PutMapping("/merchant/products/spus/{spuId}") @PreAuthorize("hasAnyRole('MERCHANT','PRODUCT_ADMIN','OPS')") ApiResponse<Void> update(@PathVariable long spuId,@Valid @RequestBody ProductRequest b,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.updateSpu(spuId,b,p,id(r),false);return ok(null,r);}
    @PostMapping("/merchant/products/spus/{spuId}/submit") @PreAuthorize("hasAnyRole('MERCHANT','PRODUCT_ADMIN','OPS')") ApiResponse<Void> submit(@PathVariable long spuId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.submit(spuId,p,id(r));return ok(null,r);}
    @PostMapping("/merchant/products/spus/{spuId}/on-sale") @PreAuthorize("hasAnyRole('MERCHANT','PRODUCT_ADMIN','OPS')") ApiResponse<Void> onSale(@PathVariable long spuId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.onSale(spuId,p,id(r));return ok(null,r);}
    @PostMapping("/merchant/products/spus/{spuId}/off-sale") @PreAuthorize("hasAnyRole('MERCHANT','PRODUCT_ADMIN','OPS')") ApiResponse<Void> offSale(@PathVariable long spuId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.offSale(spuId,p,id(r));return ok(null,r);}
    @PostMapping("/merchant/products/{spuId}/documents") @PreAuthorize("hasAnyRole('MERCHANT','PRODUCT_ADMIN','OPS')") ApiResponse<Map<String,Long>> document(@PathVariable long spuId,@Valid @RequestBody DocumentRequest b,HttpServletRequest r){return ok(Map.of("id",service.addDocument(spuId,b)),r);}
    @PostMapping(value="/merchant/products/{spuId}/documents/upload",consumes="multipart/form-data") @PreAuthorize("hasAnyRole('MERCHANT','PRODUCT_ADMIN','OPS')") ApiResponse<Map<String,Object>> uploadDocument(@PathVariable long spuId,@RequestParam String type,@RequestPart MultipartFile file,HttpServletRequest r){return ok(service.uploadDocument(spuId,type,file),r);}
    @PostMapping(value="/merchant/products/{spuId}/cover",consumes="multipart/form-data") @PreAuthorize("hasAnyRole('MERCHANT','PRODUCT_ADMIN','OPS')") ApiResponse<Map<String,Object>> merchantCover(@PathVariable long spuId,@RequestPart MultipartFile file,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.uploadCover(spuId,file,p,id(r),false),r);}
    @GetMapping("/admin/products/spus/pending") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<List<Map<String,Object>>> pending(HttpServletRequest r){return ok(service.pendingProducts(),r);}
    @GetMapping("/admin/products/spus") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<List<Map<String,Object>>> adminProducts(@RequestParam(required=false) String saleStatus,HttpServletRequest r){return ok(service.adminProducts(saleStatus),r);}
    @PutMapping("/admin/products/spus/{spuId}") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Void> adminUpdate(@PathVariable long spuId,@Valid @RequestBody ProductRequest b,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.updateSpu(spuId,b,p,id(r),true);return ok(null,r);}
    @PostMapping("/admin/products/spus/{spuId}/off-sale") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Void> adminOffSale(@PathVariable long spuId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.adminForceOffSale(spuId,p,id(r));return ok(null,r);}
    @PostMapping("/admin/products/spus/off-sale-all") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Map<String,Object>> adminOffSaleAll(@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.adminOffSaleAll(p,id(r)),r);}
    @PostMapping(value="/admin/products/spus/{spuId}/cover",consumes="multipart/form-data") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Map<String,Object>> adminCover(@PathVariable long spuId,@RequestPart MultipartFile file,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.uploadCover(spuId,file,p,id(r),true),r);}
    @PostMapping("/admin/products/spus/{spuId}/approve") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Void> approve(@PathVariable long spuId,@RequestBody(required=false) Decision b,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.approve(spuId,p,b==null?null:b.reason(),id(r));return ok(null,r);}
    @PostMapping("/admin/products/spus/{spuId}/reject") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Void> reject(@PathVariable long spuId,@Valid @RequestBody Decision b,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.reject(spuId,p,b.reason(),id(r));return ok(null,r);}
    @PostMapping("/admin/products/spus/{spuId}/violation-off-sale") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Void> violationOffSale(@PathVariable long spuId,@Valid @RequestBody Decision b,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.violationOffSale(spuId,p,b.reason(),id(r));return ok(null,r);}
    @PostMapping("/admin/products/spus/{spuId}/clear-violation") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Void> clearViolation(@PathVariable long spuId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.clearViolation(spuId,p,id(r));return ok(null,r);}

    @GetMapping("/admin/merchants/{merchantId}/category-auths") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<List<Map<String,Object>>> categoryAuths(@PathVariable long merchantId,HttpServletRequest r){return ok(service.listCategoryAuths(merchantId),r);}
    @PostMapping("/admin/merchants/{merchantId}/category-auths") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Map<String,Long>> grantCategory(@PathVariable long merchantId,@Valid @RequestBody CategoryAuthBody b,HttpServletRequest r){return ok(Map.of("id",service.grantCategoryAuth(merchantId,b.categoryId(),b.validUntil())),r);}
    @PostMapping("/admin/merchants/category-auths/{authId}/revoke") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Void> revokeCategory(@PathVariable long authId,HttpServletRequest r){service.revokeCategoryAuth(authId);return ok(null,r);}
    @GetMapping("/admin/merchants/{merchantId}/brand-auths") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<List<Map<String,Object>>> brandAuths(@PathVariable long merchantId,HttpServletRequest r){return ok(service.listBrandAuths(merchantId),r);}
    @PostMapping("/admin/merchants/{merchantId}/brand-auths") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Map<String,Long>> grantBrand(@PathVariable long merchantId,@Valid @RequestBody BrandAuthBody b,HttpServletRequest r){return ok(Map.of("id",service.grantBrandAuth(merchantId,b.brandId(),b.fileUrl(),b.validUntil())),r);}
    @PostMapping("/admin/merchants/brand-auths/{authId}/revoke") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Void> revokeBrand(@PathVariable long authId,HttpServletRequest r){service.revokeBrandAuth(authId);return ok(null,r);}

    @GetMapping("/admin/shop/flagship-certs") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN','OPS')") ApiResponse<List<Map<String,Object>>> flagshipCerts(@RequestParam(required=false)String status,HttpServletRequest r){return ok(service.listFlagshipCerts(status),r);}
    @PostMapping("/admin/shop/flagship-certs/{certId}/approve") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Void> approveFlagship(@PathVariable long certId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.approveFlagshipCert(certId,p);return ok(null,r);}
    @PostMapping("/admin/shop/flagship-certs/{certId}/reject") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Void> rejectFlagship(@PathVariable long certId,@Valid @RequestBody Decision b,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.rejectFlagshipCert(certId,p,b.reason());return ok(null,r);}
    @PostMapping("/merchant/shop/flagship-cert") @PreAuthorize("hasAnyRole('MERCHANT','PRODUCT_ADMIN')") ApiResponse<Map<String,Long>> applyFlagship(@Valid @RequestBody FlagshipApplyBody b,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(Map.of("id",service.applyFlagshipCert(p,b.brandId(),b.evidenceUrl())),r);}

    @PostMapping("/admin/catalog/categories") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Map<String,Long>> createCategory(@Valid @RequestBody CategoryRequest b,HttpServletRequest r){return ok(Map.of("id",service.createCategory(b)),r);}
    @PostMapping("/admin/catalog/brands") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Map<String,Long>> createBrand(@Valid @RequestBody BrandRequest b,HttpServletRequest r){return ok(Map.of("id",service.createBrand(b)),r);}
    @PostMapping("/admin/catalog/categories/{categoryId}/attribute-groups") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Map<String,Long>> createGroup(@PathVariable long categoryId,@Valid @RequestBody AttributeGroupRequest b,HttpServletRequest r){return ok(Map.of("id",service.createAttributeGroup(categoryId,b)),r);}
    @PostMapping("/admin/catalog/attribute-groups/{groupId}/attributes") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Map<String,Long>> createAttribute(@PathVariable long groupId,@Valid @RequestBody AttributeRequest b,HttpServletRequest r){return ok(Map.of("id",service.createAttribute(groupId,b)),r);}
    @PostMapping("/admin/catalog/attributes/{attributeId}/options") @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')") ApiResponse<Map<String,Long>> createOption(@PathVariable long attributeId,@Valid @RequestBody AttributeOptionRequest b,HttpServletRequest r){return ok(Map.of("id",service.createAttributeOption(attributeId,b)),r);}

    @PostMapping("/merchant/applications") ApiResponse<Map<String,Long>> application(@Valid @RequestBody ApplicationRequest b,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(Map.of("id",service.createApplication(b,p)),r);}
    @PostMapping("/merchant/applications/{applicationId}/submit") ApiResponse<Void> submitApplication(@PathVariable long applicationId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.submitApplication(applicationId,p);return ok(null,r);}
    @GetMapping("/admin/merchant/applications") @PreAuthorize("hasRole('SYSTEM_ADMIN')") ApiResponse<List<Map<String,Object>>> applications(HttpServletRequest r){return ok(service.applications(),r);}
    @PostMapping("/admin/merchant/applications/{applicationId}/approve") @PreAuthorize("hasRole('SYSTEM_ADMIN')") ApiResponse<Void> approveApplication(@PathVariable long applicationId,HttpServletRequest r){service.approveApplication(applicationId);return ok(null,r);}
    @PostMapping("/admin/merchant/applications/{applicationId}/reject") @PreAuthorize("hasRole('SYSTEM_ADMIN')") ApiResponse<Void> rejectApplication(@PathVariable long applicationId,@Valid @RequestBody Decision b,HttpServletRequest r){service.rejectApplication(applicationId,b.reason());return ok(null,r);}
    @GetMapping("/merchant/shop") @PreAuthorize("hasRole('MERCHANT')") ApiResponse<Map<String,Object>> merchantShop(HttpServletRequest r){return ok(service.merchantShop(),r);}
    @PatchMapping("/merchant/shop") @PreAuthorize("hasRole('MERCHANT')") ApiResponse<Void> updateShop(@Valid @RequestBody ShopRequest b,HttpServletRequest r){service.updateShop(b);return ok(null,r);}
    @GetMapping("/merchant/subaccounts") @PreAuthorize("hasRole('MERCHANT')") ApiResponse<List<Map<String,Object>>> subaccounts(HttpServletRequest r){return ok(service.subaccounts(),r);}
    @PostMapping("/merchant/subaccounts") @PreAuthorize("hasRole('MERCHANT')") ApiResponse<Map<String,Long>> addSubaccount(@Valid @RequestBody SubaccountRequest b,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(Map.of("id",service.addSubaccount(b,p)),r);}

    @GetMapping("/user/favorites") ApiResponse<List<Map<String,Object>>> favorites(@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.favorites(p.userId()),r);}
    @PostMapping("/user/favorites/{spuId}") ApiResponse<Void> favorite(@PathVariable long spuId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.favorite(p.userId(),spuId);return ok(null,r);}
    @DeleteMapping("/user/favorites/{spuId}") ApiResponse<Void> unfavorite(@PathVariable long spuId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.unfavorite(p.userId(),spuId);return ok(null,r);}
    @GetMapping("/user/shops") ApiResponse<List<Map<String,Object>>> follows(@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.follows(p.userId()),r);}
    @PostMapping("/user/shops/{shopId}") ApiResponse<Void> follow(@PathVariable long shopId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.follow(p.userId(),shopId);return ok(null,r);}
    @DeleteMapping("/user/shops/{shopId}") ApiResponse<Void> unfollow(@PathVariable long shopId,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.unfollow(p.userId(),shopId);return ok(null,r);}
    @GetMapping("/user/history") ApiResponse<List<Map<String,Object>>> history(@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.history(p.userId()),r);}
    @PostMapping("/admin/search/reindex") @PreAuthorize("hasRole('SYSTEM_ADMIN')") ApiResponse<Map<String,Integer>> reindex(HttpServletRequest r){return ok(Map.of("queued",service.reindexAll()),r);}
    @PostMapping("/admin/products/search/reindex") @PreAuthorize("hasRole('SYSTEM_ADMIN')") ApiResponse<Map<String,Integer>> productReindex(HttpServletRequest r){return ok(Map.of("queued",service.reindexAll()),r);}
    @PostMapping("/admin/products/outbox/retry") @PreAuthorize("hasRole('SYSTEM_ADMIN')") ApiResponse<Map<String,Integer>> retry(HttpServletRequest r){return ok(Map.of("retried",service.retryOutbox()),r);}
    @GetMapping("/products/internal/search-documents") ApiResponse<List<Map<String,Object>>> searchDocuments(@RequestHeader("X-Internal-Key")String key,HttpServletRequest r){if(!internalKey.equals(key))throw new com.geekmall.common.api.BusinessException("AU40304","内部服务凭证无效");return ok(service.allSearchDocuments(),r);}
    @PostMapping("/internal/products/sku-snapshots") ApiResponse<List<Map<String,Object>>> skuSnapshots(@RequestHeader("X-Internal-Key")String key,@RequestBody List<Long> skuIds,HttpServletRequest r){if(!internalKey.equals(key))throw new com.geekmall.common.api.BusinessException("AU40304","内部服务凭证无效");return ok(service.skuSnapshots(skuIds),r);}

    private static <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.success(data,id(r));} private static String id(HttpServletRequest r){return String.valueOf(r.getAttribute("requestId"));}
    record ProductRequest(@NotBlank @Size(max=255)String title,@Size(max=500)String subtitle,@NotNull Long categoryId,@NotNull Long brandId,@NotBlank String coverUrl,String descriptionHtml,String packageList,String serviceDesc,@NotEmpty List<SkuRequest> skus,List<AttrValue> attributes){}
    record SkuRequest(@NotBlank String skuCode,String barcode,@NotNull @DecimalMin("0.01")BigDecimal salePrice,@NotNull BigDecimal marketPrice,@NotNull BigDecimal costPrice,@Min(0)int weight,@Min(0)int volume,List<AttrValue> attributes){}
    record AttrValue(@NotNull Long attrId,Long optionId,@NotBlank String value){}
    record Decision(@NotBlank String reason){}
    record ApplicationRequest(@NotBlank String companyName,@NotBlank String creditCode,@NotBlank String contactName,@NotBlank String contactMobile,@NotEmpty List<String> categories){}
    record ShopRequest(@NotBlank String shopName,String logoUrl,String announcement){}
    record SubaccountRequest(@NotBlank String username,@NotBlank String roleCode){}
    record DocumentRequest(@NotBlank String type,@NotBlank String fileUrl){}
    record CategoryRequest(Long parentId,@NotBlank @Size(max=96)String name,@Min(0)int sortOrder){}
    record BrandRequest(@NotBlank @Size(max=96)String name,String logoUrl,String description,List<Long> categoryIds){}
    record AttributeGroupRequest(@NotBlank @Size(max=96)String name,@Min(0)int sortOrder){}
    record AttributeRequest(@NotBlank @Size(max=96)String name,@NotBlank String type,String unit,boolean searchable,boolean comparable,boolean saleAttribute){}
    record AttributeOptionRequest(@NotBlank @Size(max=128)String value,@Min(0)int sortOrder){}
    record CategoryAuthBody(@NotNull Long categoryId, java.sql.Date validUntil){}
    record BrandAuthBody(@NotNull Long brandId, String fileUrl, java.sql.Date validUntil){}
    record FlagshipApplyBody(@NotNull Long brandId, @NotBlank String evidenceUrl){}
}
