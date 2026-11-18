package com.jxgc.meattrace.module.trace.dto;

/** 公开页上的供应商信息（只暴露必要的，不泄露联系方式）。 */
public record SupplierBrief(String name, String licenseNo, String status) {
}
