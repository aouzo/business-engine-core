
# 业务引擎核心项目

## 1、项目目标:
1. 各种异构业务数据, 生成财务凭证
2. 有良好的扩展性
3. 对扩展和自定义的业务模板, 可验证正确性


## 2、当前完成功能:
1. 项目独立于现有业务系统
2. 模板基本验证逻辑
3. 使用现有模板生成凭证

## 3、待完成功能:
0. 与现有项目的非源码的继承
1. 完整的模板静态验证
2. 完整的凭证生成的验证逻辑
3. 项目内的凭证验证过程: 生成业务dto -> 审核生成凭证 -> 凭证正确性验证
4. 上层业务系统, 不同DTO的无缝支持 
5. 模板的扩展设计: 影响因素\取数公式\行业
6. 前端维护界面, 支持系统级的运营支持, 可增删改\合并等处理
7. 支持用户自定义扩展个性化模板
8. 模板数据在工程内存储
9. 对于dto是否符合业务模板的通用校验(业务对象从Excel导入等场景)


## 4、附:
### 模板静态校验规则(持续扩展):
1. <del>字段</del>交叉规则--各模板是否同时结算或不结算 `TemplateSettleValidator`  
2. 交叉规则--影响因素字段的必填是否一致<del>（凭证模板没有“默认”则为必填）</del>，不必填并且凭证模板没有默认 `TemplateInfluenceValidator`  
3. 交叉规则--支持的行业是否一致 `TemplateIndustryValidator`  
4. 字段规则--遗漏票据类型 （tactics 中需要有对应记录，需要导入时处理）、错漏税率（税率必填，special 中要有对应记录）、错漏纳税人身份（至少一个勾选 tactics）、错漏行业（规则 c）、错漏字段  
5. 凭证规则--凭证影响因素和扩展影响因素是否一致，extendattr 有值 influence列必须有值，单独排序除外`AcmDocAccountTemplate`  
6. 凭证规则--是否缺少"默认"，和 b 相关  
7. 凭证规则--取数表达式是否合法  
8. 字段规则--勾选“资产分类”、“存货”、“资产”字段，必须在规则表“业务和存货对照表”、“资产分类”对照表里能找到规则  `AcmUITemplate`
9. 税率必填的情况下, 是否有明确可选税率范围 规则 d 中错漏税率
10.收入类型对应属性表--收入类型的业务（需要税务属性的业务规则待定）需要有对应属性，税务取数
11.凭证规则--凭证金额来源使用的字段需要对应的元数据字段显示
12.凭证规则--存货属性影响因素和业务类型存货属性关系表校验，如果有存货属性影响因素，关系表中存在的存货属性必须要有对应的记录（关系表不区分行业，存货属性是区分行业的，有问题），或者有默认记录
13.凭证规则--凭证模板使用的科目在当前行业不存在
14.交叉规则--凭证模板使用的行业，元数据模板没有勾选

## 生成凭证校验规则(持续扩展):
1. 构建dto的模拟数据  
2. 遍历各个影响因素  
3. 根据模板验证凭证科目和金额是否正确`DocEntryValidator`  