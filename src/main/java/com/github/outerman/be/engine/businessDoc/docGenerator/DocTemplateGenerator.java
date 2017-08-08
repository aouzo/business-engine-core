package com.github.outerman.be.engine.businessDoc.docGenerator;


import com.github.outerman.be.api.constant.*;
import com.github.outerman.be.api.vo.*;
import com.github.outerman.be.engine.businessDoc.BusinessUtil;
import com.github.outerman.be.engine.businessDoc.dataProvider.ITemplateProvider;
import com.github.outerman.be.api.dto.FiDocGenetateResultDto;
import com.github.outerman.be.api.dto.FiDocGenetateResultDto.ReceiptResult;
import com.github.outerman.be.engine.businessDoc.businessTemplate.AmountGetter;
import com.github.outerman.be.engine.businessDoc.businessTemplate.BusinessTemplate;
import com.github.outerman.be.engine.businessDoc.businessTemplate.TemplateManager;
import com.github.outerman.be.engine.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by shenxy on 16/12/28.
 * 生成凭证的工具类
 */
@Component
public class DocTemplateGenerator {
    private final static Logger logger = LoggerFactory.getLogger(DocTemplateGenerator.class);

    @Autowired
    private TemplateManager templateManager;

    public  final String EMPTY = "";
    private final String AMOUNT_TAXINCLUSIVEAMOUNT = "taxInclusiveAmount";

    public FiDocGenetateResultDto sortConvertVoucher(SetOrg setOrg, Long userId, String userName, List<AcmSortReceipt> receiptList, ITemplateProvider templateProvider) {
        if(receiptList == null || receiptList.isEmpty() || setOrg == null){//判断返回值是否为空
            throw new BusinessEngineException(ErrorCode.ENGINE_DOC_GENETARE_EMPTY_ACM_ERROR_CODE, ErrorCode.ENGINE_DOC_GENETARE_EMPTY_ACM_ERROR_MSG);
        }

        FiDocDto fiDocDto;
        List<FiDocDto> fiDocList = new ArrayList<>();
        SetCurrency currency = templateProvider.getBaseCurrency(setOrg.getId());
        FiDocGenetateResultDto resultDto = new FiDocGenetateResultDto();

        for (AcmSortReceipt acmSortReceipt : receiptList) {
            if (!checkSpecialReceipt(acmSortReceipt, resultDto)) {
                continue;
            }
            //创建凭证对象
            fiDocDto = new FiDocDto();
            //填写表头
            fillDocHead(fiDocDto, acmSortReceipt);
            // 把业务明细, 根据业务编码, 做重新排序
            List<AcmSortReceiptDetail> acmSortReceiptDetailList = reorderReceiptDetailList(acmSortReceipt.getAcmSortReceiptDetailList());

            for (int i = 0 ; i < acmSortReceiptDetailList.size() ; i++) {
            	AcmSortReceiptDetail acmSortReceiptDetail = acmSortReceiptDetailList.get(i);
                BusinessTemplate businessTemplate = templateManager.fetchBusinessTemplate(setOrg.getId(), acmSortReceiptDetail.getBusinessCode(), templateProvider);

                // 1.取出业务类型对应的模板
                if (businessTemplate == null || businessTemplate.getDocAccountTemplate() == null
                        || businessTemplate.getDocAccountTemplate().getDocTemplateDto() == null
                        || businessTemplate.getDocAccountTemplate().getDocTemplateDto().getAllPossibleTemplate() == null
                        || businessTemplate.getDocAccountTemplate().getDocTemplateDto().getAllPossibleTemplate().isEmpty()) {
                    continue;
                }

                List<DocAccountTemplateItem> fiBillDocTemplateList = businessTemplate.getDocAccountTemplate()
                        .getTemplate(setOrg.getId(), setOrg.getAccountingStandards().intValue(), setOrg.getIndustry(), setOrg.getVatTaxpayer(), acmSortReceiptDetail);

                // TODO 科目编码处理
                List<String> codeList = new ArrayList<>();
                for (DocAccountTemplateItem docTemplate : fiBillDocTemplateList) {
                    codeList.add(docTemplate.getAccountCode());
                }
                Map<String, FiAccount> codeMap = templateProvider.getAccountCode(setOrg.getId(), codeList, acmSortReceiptDetailList);
                for (int h = 0 ; h < fiBillDocTemplateList.size(); h++ ) {
                    DocAccountTemplateItem fiBillDocTemplate = fiBillDocTemplateList.get(h);
                    //根据反馈建议, 调整模板
                    fiBillDocTemplate = updateTemplateByAdvice(acmSortReceiptDetail, fiBillDocTemplate, resultDto, codeMap, templateProvider);

                	if(fiBillDocTemplate == null){
                        throw new BusinessEngineException(ErrorCode.ENGINE_DOC_GENETARE_EMPTY_BUSINESS_ERROR_CODE, ErrorCode.ENGINE_DOC_GENETARE_EMPTY_BUSINESS_ERROR_MSG);
                	}
                	
                    // 取金额方式
                    Double amount = AmountGetter.getAmount(acmSortReceiptDetail, fiBillDocTemplate);
                    if(amount.equals(0D) || amount.equals(0.0)){//金额为零的跳过
                        continue;
                    }

                    StringBuilder fuz = new StringBuilder();
                    // 建立辅助核算
                    Map<String,String> auxInfo = new HashMap<>();
                    setAuxInfo(setOrg.getVatTaxpayer(), currency, acmSortReceipt, acmSortReceiptDetail, fiBillDocTemplate, fuz, auxInfo);

                    Boolean isSort = true;
                    //查看是否需要特殊排序: 所有"本表"平的业务,都特殊排序
                    // "工资-发放-实发工资"特殊处理, 虽然是"结算方式",但是和其他"本表"的业务,一起单独排序
                    if(fiBillDocTemplate.getIsSettlement() != null && !fiBillDocTemplate.getIsSettlement() || BusinessTypeUtil.SPECIAL_ORDER.contains(fiBillDocTemplate.getBusinessCode())){
                        isSort = false;
                    }

                    String accountCode = fuz.insert(0, fiBillDocTemplate.getAccountCode()).toString();
                    Long businessCode = fiBillDocTemplate.getBusinessCode();
                    // "账户内"互转的三项业务, 固定不做合并
                    if (BusinessTypeUtil.NOT_MERGE_BUSINESS.contains(fiBillDocTemplate.getBusinessCode())) {
                        accountCode += "_ran"+i;
                        businessCode = null;
                    }

                    if (!fiBillDocTemplate.getDirection()) {//借
                        fiDocDto.addEntryJie(accountCode, amount, (auxInfo.isEmpty() ? null : auxInfo), isSort, businessCode);
                    } else {//贷
                        fiDocDto.addEntryDai(accountCode, amount, (auxInfo.isEmpty() ? null : auxInfo), isSort, businessCode);
                    }
                }
            }

            if(fiDocDto.getEntrys() == null || fiDocDto.getEntrys().isEmpty()){//如果业务类型凭证为空 跳出
                addDocToList(fiDocList, fiDocDto, acmSortReceipt.getDocId(), setOrg.getId(), userId);
                continue;
            }
            List<AcmSortReceiptSettlestyle> acmSortReceiptSettlestyleList = acmSortReceipt.getAcmSortReceiptSettlestyleList();

            if(acmSortReceiptSettlestyleList == null || acmSortReceiptSettlestyleList.isEmpty()) {
                logger.info("理票单生成模板,参数:"+acmSortReceipt.getId()+"结算方式为空");
                addDocToList(fiDocList, fiDocDto, acmSortReceipt.getDocId(), setOrg.getId(), userId);
                continue;
            }

            //TODO: 结算方式暂时没有和businessCode挂钩, 所以取任何一个都会返回所有
            BusinessTemplate businessTemplate = templateManager.fetchBusinessTemplate(setOrg.getId(), acmSortReceiptDetailList.get(0).getBusinessCode(), templateProvider);
            // TODO 科目编码处理
            List<String> accountCodeList = businessTemplate.getPaymentTemplate().getAccountCodeList();
            Map<String, FiAccount> codeMap = templateProvider.getAccountCode(setOrg.getId(), accountCodeList, acmSortReceiptDetailList);
            //查询结算方式生成凭证
            for (int i = 0 ; i< acmSortReceiptSettlestyleList.size(); i++) {
            	AcmSortReceiptSettlestyle sett = acmSortReceiptSettlestyleList.get(i);

                //拿出结算方式查出模板
                PaymentTemplateItem acmPayDocTemplate = businessTemplate.getPaymentTemplate().getTemplate(acmSortReceipt, sett);// acmPayTemplate.getTemplate(acmSortReceipt, isJieDai, sett,payMap);
                if(acmPayDocTemplate == null){
                	throw new BusinessEngineException(ErrorCode.ENGINE_DOC_GENETARE_EMPTY_PAY_ERROR_CODE, ErrorCode.ENGINE_DOC_GENETARE_EMPTY_PAY_ERROR_MSG);
                }

                String memo = getMemo(acmSortReceipt, acmPayDocTemplate);

                //根据反馈建议, 调整模板
                acmPayDocTemplate = getPayTemplateByAdvice(sett, acmPayDocTemplate, codeMap, templateProvider);

                //模板为空,或者模板不对
                if (acmPayDocTemplate == null || (!acmPayDocTemplate.getSettlement().equals(sett.getSettleStyle()) &&
                        !acmPayDocTemplate.getPaymentsType().toString().substring(0, 5).equals(acmSortReceipt.getPaymentsType().toString()))) {
                    continue;
                }

                Boolean direction = acmPayDocTemplate.getDirection();

                //查看科目是否需要颠倒 ,如果需颠倒直接颠倒
                if (acmPayDocTemplate.getReversal() && sett.getTaxInclusiveAmount() < 0) {
                    //颠倒借贷
                    direction = !direction;
                    //颠倒金额
                    sett.setTaxInclusiveAmount(sett.getTaxInclusiveAmount() * -1);
                }
                //取金额方式
                Double amount = 0D;
                if (AMOUNT_TAXINCLUSIVEAMOUNT.equals(acmPayDocTemplate.getFundSource())) {//含税金额
                    amount = sett.getTaxInclusiveAmount();
                }

                // 建立辅助核算
                Map<String, String> auxInfo = new HashMap<>();
                StringBuilder fuz = new StringBuilder("");
                setSettlementAuxInfo(currency, sett, acmPayDocTemplate, auxInfo, fuz,memo);

                // 取科目
                if (!direction) {//借 07.3.31 增加 _ran 结算科目不合并
                    fiDocDto.addEntryJie(fuz.insert(0, acmPayDocTemplate.getSubjectDefault()+"_ran"+i).toString(), amount, (auxInfo.isEmpty() ? null : auxInfo), true, -1L);
                } else {//贷
                    fiDocDto.addEntryDai(fuz.insert(0, acmPayDocTemplate.getSubjectDefault()+"_ran"+i).toString(), amount, (auxInfo.isEmpty() ? null : auxInfo), true, -1L);
                }
            }

            addDocToList(fiDocList, fiDocDto, acmSortReceipt.getDocId(), setOrg.getId(), userId);
        }
        // 支持更新生成
        fiDocList.forEach(docDto -> {
            if (docDto.getDocId() != null) {
                resultDto.getToUpdateFiDocList().add(docDto);
            }
            else {
                resultDto.getToInsertFiDocList().add(docDto);
            }
        });

        return resultDto;
    }

    private PaymentTemplateItem getPayTemplateByAdvice(AcmSortReceiptSettlestyle sett, PaymentTemplateItem acmPayDocTemplate, Map<String, FiAccount> codeMap, ITemplateProvider templateProvider) {
        return templateProvider.requestAdvice(acmPayDocTemplate, codeMap, sett);
    }


    private DocAccountTemplateItem updateTemplateByAdvice(AcmSortReceiptDetail acmSortReceiptDetail, DocAccountTemplateItem fiBillDocTemplate, FiDocGenetateResultDto resultDto,
            Map<String, FiAccount> codeMap, ITemplateProvider templateProvider) {

        Long bankAccountId;
        if ((String.valueOf(acmSortReceiptDetail.getBusinessCode()).equals(BusinessCode.BUSINESS_402000)
                && "accountInAttr".equals(fiBillDocTemplate.getInfluence()))
                || (String.valueOf(acmSortReceiptDetail.getBusinessCode()).equals(BusinessCode.BUSINESS_401050)
                && "1002".equals(fiBillDocTemplate.getAccountCode()) && acmSortReceiptDetail.getInBankAccountId() != null)) {
            bankAccountId = acmSortReceiptDetail.getInBankAccountId();
        } else {
            bankAccountId = acmSortReceiptDetail.getBankAccountId();
        }
        List<ReceiptResult> fiDocReturnFailList = new ArrayList<>();
        fiBillDocTemplate = templateProvider.requestAdvice(fiBillDocTemplate, codeMap, bankAccountId, fiDocReturnFailList, acmSortReceiptDetail.getSortReceiptId());
        resultDto.getFailedReceipt().addAll(fiDocReturnFailList);
        return fiBillDocTemplate;
    }


    private void fillDocHead(FiDocDto fiDocDto, AcmSortReceipt acmSortReceipt) {
        String dateStr;
        // 来源单据id 设为理票单ID
        fiDocDto.setSourceVoucherId(acmSortReceipt.getSourceVoucherId());
        // 来源单据code 设为理票单code
        fiDocDto.setSourceVoucherCode(acmSortReceipt.getSourceVoucherCode());
        // 单据类型-理票
        fiDocDto.setDocSourceTypeId(acmSortReceipt.getSourceVoucherTypeId());
        // 原始单据数量
        fiDocDto.setAttachedVoucherNum(acmSortReceipt.getAppendNum());
        // 凭证日期
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        dateStr = format.format(acmSortReceipt.getInAccountDate());
        fiDocDto.setVoucherDate(dateStr);
        // 如果曾经生成过,保留凭证号
        if (!StringUtil.isEmpty(acmSortReceipt.getDocCodeBak())) {
            fiDocDto.setCode(acmSortReceipt.getDocCodeBak());
        }
    }

    // 把业务明细, 根据业务编码, 做重新排序
    private List<AcmSortReceiptDetail> reorderReceiptDetailList(List<AcmSortReceiptDetail> acmSortReceiptDetailList) {
        LinkedHashMap<Long, List<AcmSortReceiptDetail>> retMap = new LinkedHashMap<>();
        if (acmSortReceiptDetailList == null || acmSortReceiptDetailList.isEmpty()) {
            return acmSortReceiptDetailList;
        }

        for (AcmSortReceiptDetail acmSortReceiptDetail : acmSortReceiptDetailList) {
            Long busiType = acmSortReceiptDetail.getBusinessType();
            if (retMap.containsKey(busiType)) {
                retMap.get(busiType).add(acmSortReceiptDetail);
            }
            else {
                List<AcmSortReceiptDetail> list = new ArrayList<>();

                list.add(acmSortReceiptDetail);
                retMap.put(busiType, list);
            }
        }

        List<AcmSortReceiptDetail> ret = new ArrayList<>();
        for (List<AcmSortReceiptDetail> acmSortReceiptDetails : retMap.values()) {
            ret.addAll(acmSortReceiptDetails);
        }
        return ret;
    }

    // TODO: 获取分录"摘要", 目前逻辑是这样的, 是否后续优化?
    private String getMemo(AcmSortReceipt acmSortReceipt, PaymentTemplateItem acmPayDocTemplate) {
        String mome;
        if(acmSortReceipt.getAcmSortReceiptDetailList().size() == 1){
            mome = acmSortReceipt.getAcmSortReceiptDetailList().get(0).getMemo();
        }else{
            mome = acmPayDocTemplate.getSubjectType();
        }
        return mome;
    }

    private void addDocToList(List<FiDocDto> fiDocList, FiDocDto fiDocDto, Long docId, Long orgId, Long userId) {
        //支持更新
        if (docId != null) {
            fiDocDto.setOrgId(orgId);
            fiDocDto.setDocId(docId);
            fiDocDto.setMakerId(userId);
        }

        fiDocList.add(fiDocDto);
    }

    private boolean checkSpecialReceipt(
            AcmSortReceipt acmSortReceipt,
            FiDocGenetateResultDto resultDto)  {

        if(acmSortReceipt.getPaymentsType() == AcmConst.PAYMENTSTYPE_60){//如果是特殊类型跳出本次循环
            FiDocGenetateResultDto.ReceiptResult receiptResult = new FiDocGenetateResultDto.ReceiptResult();
            receiptResult.setReceipt(acmSortReceipt);
            receiptResult.setMsg(ErrorCode.ENGINE_DOC_GENETARE_UNRESOVE_ERROR_MSG);
            resultDto.getUnResolvedReceipt().add(receiptResult);
            return false;
        }
        List<AcmSortReceiptDetail> acmSortReceiptDetailList = acmSortReceipt.getAcmSortReceiptDetailList();
        if(acmSortReceiptDetailList == null || acmSortReceiptDetailList.isEmpty()){
            FiDocGenetateResultDto.ReceiptResult receiptResult = new FiDocGenetateResultDto.ReceiptResult();
            receiptResult.setReceipt(acmSortReceipt);
            receiptResult.setMsg(ErrorCode.ENGINE_DOC_GENETARE_EMPTY_DETAIL_ERROR_MSG);
            resultDto.getFailedReceipt().add(receiptResult);
            return false;
        }

        return true;
    }

    private void setSettlementAuxInfo(SetCurrency currency, AcmSortReceiptSettlestyle sett, PaymentTemplateItem acmPayDocTemplate, Map<String, String> auxInfo, StringBuilder fuz,String mome) {
        if(acmPayDocTemplate.getIsAuxAccCalc() != null && acmPayDocTemplate.getIsAuxAccCalc()){

            //部门，人员，客户，存货，账号
            if(acmPayDocTemplate.getIsAuxAccPerson() ){//人员
                if(sett.getEmployee() != null && sett.getEmployee() != 0){
                    auxInfo.put("employee", sett.getEmployee()+EMPTY);
                    fuz.append("_yy").append(sett.getEmployee());
                }
            }
            if(acmPayDocTemplate.getIsAuxAccCustomer()){//客户
                if(sett.getConsumer() != null){
                    auxInfo.put("consumer", sett.getConsumer()+EMPTY);
                    fuz.append("_kh").append(sett.getConsumer());
                }
            }
            if(acmPayDocTemplate.getIsAuxAccSupplier()){//供应商
                if(sett.getVendor() != null){
                    auxInfo.put("vendor", sett.getVendor()+EMPTY);
                    fuz.append("_gys").append(sett.getVendor());

                }
            }
            if(acmPayDocTemplate.getIsAuxAccBankAccount()){//银行账号
                if(sett.getBankAccountId() != null){
                    auxInfo.put("bank", sett.getBankAccountId()+EMPTY);
                    fuz.append("_yhzh").append(sett.getBankAccountId());

                }
            }
        }
        if(acmPayDocTemplate.getIsMultiCalc() != null){//多币种
        	if(currency.getId() != null){
        		auxInfo.put("currency", currency.getId()+EMPTY);
        		fuz.append("_dbz").append(currency.getId());
        	}
        }

        //摘要
        fuz.append("_zy").append(mome);
        if(!StringUtil.isEmpty(mome)){
            auxInfo.put("memo", mome);
        }else{
        	if(acmPayDocTemplate.getSubjectType() != null){
        		auxInfo.put("memo", acmPayDocTemplate.getSubjectType()+EMPTY);
        	}
        }
        //科目id
        if(acmPayDocTemplate.getAccountId() != null){
            auxInfo.put("accountId", acmPayDocTemplate.getAccountId()+EMPTY);
        }
        //科目code
        if(acmPayDocTemplate.getSubjectDefault() != null){
            auxInfo.put("accountCode", acmPayDocTemplate.getSubjectDefault()+EMPTY);
        }
    }

    private void setAuxInfo(Long vatTaxpayer, SetCurrency currency, AcmSortReceipt acmSortReceipt, AcmSortReceiptDetail acmSortReceiptDetail, DocAccountTemplateItem fiBillDocTemplate, StringBuilder fuz, Map<String, String> auxInfo) {
        if(fiBillDocTemplate.getIsAuxAccCalc() != null && fiBillDocTemplate.getIsAuxAccCalc()){
            if(fiBillDocTemplate.getIsAuxAccDepartment()){//部门
                if(acmSortReceiptDetail.getDepartment() != null){
                    auxInfo.put("department", acmSortReceiptDetail.getDepartment()+EMPTY);
                    fuz.append("_bm").append(acmSortReceiptDetail.getDepartment());
                }
            }
            if(fiBillDocTemplate.getIsAuxAccPerson()){//人员
                if(acmSortReceiptDetail.getEmployee() != null ){
                    auxInfo.put("employee", acmSortReceiptDetail.getEmployee()+EMPTY);
                    fuz.append("_yy").append(acmSortReceiptDetail.getEmployee());
                }
            }
            if(fiBillDocTemplate.getIsAuxAccCustomer()){//客户
                if(acmSortReceiptDetail.getConsumer() != null){
                    auxInfo.put("consumer", acmSortReceiptDetail.getConsumer()+EMPTY);
                    fuz.append("_kh").append(acmSortReceiptDetail.getConsumer());
                }
            }
            if(fiBillDocTemplate.getIsAuxAccSupplier()){//供应商
                if(acmSortReceiptDetail.getVendor() != null){
                    auxInfo.put("vendor", acmSortReceiptDetail.getVendor()+EMPTY);
                    fuz.append("_gys").append(acmSortReceiptDetail.getVendor());
                }
            }
            if(fiBillDocTemplate.getIsAuxAccInventory()){//存货
                if(acmSortReceiptDetail.getInventory() != null){
                    auxInfo.put("inventory", acmSortReceiptDetail.getInventory()+EMPTY);
                    fuz.append("_ch").append(acmSortReceiptDetail.getInventory());
                }
                if(acmSortReceiptDetail.getAssetId() != null){
                	auxInfo.put("inventory", acmSortReceiptDetail.getAssetId()+EMPTY);
                	fuz.append("_ch").append(acmSortReceiptDetail.getAssetId());
                }
            }
            if(fiBillDocTemplate.getIsAuxAccProject()){//项目
                if(acmSortReceiptDetail.getProject() != null){
                    auxInfo.put("project", acmSortReceiptDetail.getProject()+EMPTY);
                    fuz.append("_xm").append(acmSortReceiptDetail.getProject());
                }
            }
            if(fiBillDocTemplate.getIsQuantityCalc()){//数量
                if(acmSortReceiptDetail.getCommodifyNum() != null){
                    auxInfo.put("commodifyNum", acmSortReceiptDetail.getCommodifyNum()+EMPTY);
                    fuz.append("_sl").append(acmSortReceiptDetail.getCommodifyNum());
                }
            }
//							if(fiBillDocTemplate.getIsAuxAccBankAccount()){//银行账号
//								auxInfo.put("bank", acmSortReceiptDetail.getBankAccountId()+EMPTY);
//								fuz.append(acmSortReceiptDetail.getBankAccountId());
//							}

            //银行账号
            if("402000".equals(fiBillDocTemplate.getBusinessCode().toString()) && "A".equals(fiBillDocTemplate.getFlag())){
                fuz.append("_yhzh").append(acmSortReceiptDetail.getInBankAccountId());
                if(acmSortReceiptDetail.getInBankAccountId() != null){
                    auxInfo.put("bank", acmSortReceiptDetail.getInBankAccountId()+EMPTY);
                }
            }else{
                fuz.append("_yhzh").append(acmSortReceiptDetail.getBankAccountId());
                if(acmSortReceiptDetail.getBankAccountId() != null){
                    auxInfo.put("bank", acmSortReceiptDetail.getBankAccountId()+EMPTY);
                }
            }

        }
        if(acmSortReceipt.getSourceVoucherTypeId() != null && !BusinessTypeUtil.GONGZI_VOUCHERTYPE_LIST.contains(acmSortReceipt.getSourceVoucherTypeId())){
            //科目+辅助核算+业务类型+部门属性+人员属性+借款期限+账户属性流入+账户属性流出+纳税人+资产类别+罚款性质
            if(fiBillDocTemplate.getInfluence() != null){//是否有影响因素

                switch (fiBillDocTemplate.getInfluence()) {
                    case "departmentAttr":{
                        //部门属性
                        if(acmSortReceiptDetail.getDepartmentProperty() != null){
                            fuz.append("_bmsx").append(acmSortReceiptDetail.getDepartmentProperty());
                        }
                    }
                    break;
                    case "departmentAttr,personAttr":{
                        //部门属性
                        if(acmSortReceiptDetail.getDepartmentProperty() != null){
                            fuz.append("_bmsx").append(acmSortReceiptDetail.getDepartmentProperty());
                        }
                        //人员属性
                        if(acmSortReceiptDetail.getEmployeeAttribute() != null){
                            fuz.append("_rysx").append(acmSortReceiptDetail.getEmployeeAttribute());
                        }
                    }
                    break;
                    case "vatTaxpayer":{
                        //纳税人性质
                        if(vatTaxpayer != null){
                            fuz.append("_nsr").append(vatTaxpayer);
                        }
                    }
                    break;
                    case "punishmentAttr":{//罚款性质
                        if(acmSortReceiptDetail.getPenaltyType() != null){
                            fuz.append("_fkxz").append(acmSortReceiptDetail.getPenaltyType());
                        }
                    }
                    break;
                    case "borrowAttr":{//借款期限
                        //借款期限
                        if(acmSortReceiptDetail.getLoanTerm() != null){
                            fuz.append("_jkqx").append(acmSortReceiptDetail.getLoanTerm());
                        }
                    }
                    break;
                    case "commodityAttr":{//商品或服务名称
                    }
                    break;
                    case "assetAttr":{//资产类别
                        if(acmSortReceiptDetail.getAssetAttr() != null){
                            fuz.append("_zclb").append(acmSortReceiptDetail.getAssetAttr());
                        }
                    }
                    break;
                    case "accountInAttr":{//账户属性
                        //账户流入
                        if(acmSortReceiptDetail.getInBankAccountId() != null){
                            fuz.append("_zhlr").append(acmSortReceiptDetail.getInBankAccountId());
                        }
                    }
                    break;
                    case "accountOutAttr":{//账户属性
                        //账户流入
                        if(acmSortReceiptDetail.getBankAccountId() != null){
                            fuz.append("_zhlc").append(acmSortReceiptDetail.getBankAccountId());
                        }
                    }
                    break;

                    default:
                        break;
                }

            }

        }
        
        //业务类型+  712 zbs 不管需不需要业务类型都给凭证传递过去
        fuz.append("_ywlx").append(acmSortReceiptDetail.getBusinessType());
        if(acmSortReceiptDetail.getBusinessType() != null){
        	auxInfo.put("businessType", acmSortReceiptDetail.getBusinessType()+EMPTY);
        }

        //单价
    	if(BusinessUtil.paymentDirection(acmSortReceiptDetail.getBusinessCode()) == 1 ||
                (acmSortReceiptDetail.getIsDeduction() != null && acmSortReceiptDetail.getIsDeduction() == 1)) {// 是  看是否抵扣然后传递不同的单价
        	if(acmSortReceiptDetail.getPrice() != null){
                auxInfo.put("price", acmSortReceiptDetail.getPrice()+EMPTY);
            }
        }else{
        	if(acmSortReceiptDetail.getTaxInclusivePrice() != null){
                auxInfo.put("price", acmSortReceiptDetail.getTaxInclusivePrice()+EMPTY);
            }
        }
        
        
        //摘要
    	fuz.append("_zy").append(acmSortReceiptDetail.getMemo());
        if(acmSortReceiptDetail.getMemo() != null){
            auxInfo.put("memo", acmSortReceiptDetail.getMemo()+EMPTY);
        }
        //科目id
        if(fiBillDocTemplate.getAccountId() != null){
            auxInfo.put("accountId", fiBillDocTemplate.getAccountId()+EMPTY);
        }
        //科目code
        if(fiBillDocTemplate.getAccountCode() != null){
            auxInfo.put("accountCode", fiBillDocTemplate.getAccountCode()+EMPTY);
        }
        //科目flag
        if(fiBillDocTemplate.getFlag() != null){
        	auxInfo.put("flag", fiBillDocTemplate.getFlag());
        }
        if(fiBillDocTemplate.getIsMultiCalc() != null){//多币种
            if(currency.getId() != null){
                auxInfo.put("currency", currency.getId()+EMPTY);
                fuz.append("_dbz").append(currency.getId());
            }
        }
    }
}