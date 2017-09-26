package com.github.outerman.be.engine.businessDoc.businessTemplate;

import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.github.outerman.be.api.constant.AcmConst;
import com.github.outerman.be.api.dto.BusinessTemplateDto;
import com.github.outerman.be.api.dto.TemplateValidateResultDto;
import com.github.outerman.be.api.vo.SetOrg;
import com.github.outerman.be.engine.businessDoc.dataProvider.ITemplateProvider;
import com.github.outerman.be.engine.businessDoc.validator.IValidatable;
import com.github.outerman.be.engine.businessDoc.validator.ValidatorManager;
import com.github.outerman.be.engine.util.StringUtil;

/**
 * Created by shenxy on 7/7/17.
 * 业务类型--主模板
 */
@Component(AcmConst.BUSINESS_TEMPLATE)
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class BusinessTemplate implements IValidatable {
    @Autowired
    private AcmDocAccountTemplate docAccountTemplate;
    @Autowired
    private AcmPaymentTemplate paymentTemplate;
    @Autowired
    private AcmUITemplate uiTemplate;
    @Autowired
    private ValidatorManager validatorManager;

    private BusinessTemplateDto businessTemplateDto;

    //初始化方法, orgId可能为0; 如不为0, 则初始化公共模板(orgId=0)以及个性化模板
    public void init(SetOrg org, Long businessCode, ITemplateProvider templateProvider) {
        docAccountTemplate.init(org, businessCode, templateProvider);
        paymentTemplate.init(org, businessCode, templateProvider);
        uiTemplate.init(org, businessCode, templateProvider);

        businessTemplateDto = new BusinessTemplateDto();
        businessTemplateDto.setOrg(org);
        businessTemplateDto.setBusinessCode(businessCode);
        businessTemplateDto.setDocAccountTemplate(docAccountTemplate.getDocTemplateDto());
        businessTemplateDto.setPaymentTemplate(paymentTemplate.getPaymentTemplateDto());
        businessTemplateDto.setUiTemplate(uiTemplate.getUiTemplateDto());
    }

    @Override
    public String validate() {
        TemplateValidateResultDto result = new TemplateValidateResultDto();
        //先分别校验
        result.setDocTemplateMessage(docAccountTemplate.validate());
        result.setPayDocTemplateMessage(paymentTemplate.validate());
        result.setUiTemplateMessage(uiTemplate.validate());
        if (!result.getResult()) {
            return result.getErrorMessage();
        }

        //再组合校验, 例如:
//        a）各模板是否同时结算或不结算
//        b）影响因素字段的必填是否一致（凭证模板没有“默认”则为必填）
//        c）支持的行业是否一致
        String errorMessage = validatorManager.getTemplateValidators()
                .parallelStream()
                .map(validator -> validator.validate(docAccountTemplate.getDocTemplateDto(),
                        paymentTemplate.getPaymentTemplateDto(),
                        uiTemplate.getUiTemplateDto()))
                .filter(message -> !StringUtil.isEmpty(message))
                .collect(Collectors.joining("\n"));
        return errorMessage;
    }

    public Long getBusinessCode() {
        return businessTemplateDto.getBusinessCode();
    }


    public BusinessTemplateDto getBusinessTemplateDto() {
        return businessTemplateDto;
    }

    public void setBusinessTemplateDto(BusinessTemplateDto businessTemplateDto) {
        this.businessTemplateDto = businessTemplateDto;
    }

    public AcmDocAccountTemplate getDocAccountTemplate() {
        return docAccountTemplate;
    }

    public void setDocAccountTemplate(AcmDocAccountTemplate docAccountTemplate) {
        this.docAccountTemplate = docAccountTemplate;
    }

    public AcmPaymentTemplate getPaymentTemplate() {
        return paymentTemplate;
    }

    public void setPaymentTemplate(AcmPaymentTemplate paymentTemplate) {
        this.paymentTemplate = paymentTemplate;
    }

    public AcmUITemplate getUiTemplate() {
        return uiTemplate;
    }

    public void setUiTemplate(AcmUITemplate uiTemplate) {
        this.uiTemplate = uiTemplate;
    }
}
