package com.github.outerman.be.api.dto;

import com.github.outerman.be.api.vo.PaymentTemplateItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by shenxy on 16/12/28.
 * 流水账生成凭证的结算类 相关模板
 */
public class AcmPaymentTemplateDto {

    private Map<String, PaymentTemplateItem> payMap = new HashMap<>();  //可能会用到的凭证模板
    private List<String> codeList = new ArrayList<>();      //可能用到的科目
    private Long orgId;
    private Long businessCode;

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public Long getBusinessCode() {
        return businessCode;
    }

    public void setBusinessCode(Long businessCode) {
        this.businessCode = businessCode;
    }

    public Map<String, PaymentTemplateItem> getPayMap() {
        return payMap;
    }

    public void setPayMap(Map<String, PaymentTemplateItem> payMap) {
        this.payMap = payMap;
    }

    public List<String> getCodeList() {
        return codeList;
    }

    public void setCodeList(List<String> codeList) {
        this.codeList = codeList;
    }
}