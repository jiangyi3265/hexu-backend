package com.ruoyi.hexu;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Anonymous @RestController @RequestMapping("/hexu/app")
public class HexuAppController {
    private final HexuIdentity auth;private final HexuStore db;private final HexuCatalogService catalog;private final HexuOrderService orders;private final HexuFinanceService finance;private final HexuWorkflowService workflow;private final HexuCommands commands;private final HexuAccountService accounts;private final HexuPolicies policies;private final HexuUiConfigService uiConfig;
    public HexuAppController(HexuIdentity auth,HexuStore db,HexuCatalogService catalog,HexuOrderService orders,HexuFinanceService finance,HexuWorkflowService workflow,HexuCommands commands,HexuAccountService accounts,HexuPolicies policies,HexuUiConfigService uiConfig){this.accounts=accounts;this.auth=auth;this.db=db;this.catalog=catalog;this.orders=orders;this.finance=finance;this.workflow=workflow;this.commands=commands;this.policies=policies;this.uiConfig=uiConfig;}
    static Map<String,Object> ok(Object value){return map("code",200,"data",value);}
    @GetMapping("/shops") public Object shops(){return ok(catalog.shops());}
    @GetMapping("/guest/products") public Object guestProducts(@RequestParam long shopId){return ok(catalog.guestProducts(shopId));}
    /**
     * Public storefront configuration.  It is deliberately read from the
     * published decoration/page_schema documents so the mini program never
     * needs to carry tenant-specific copy, navigation or page blocks.
     */
    @GetMapping("/storefront") public Object storefront(@RequestParam long shopId){
        db.shop(shopId);
        Map<String,Object> decoration=new LinkedHashMap<>(db.config(shopId,"decoration"));
        decoration.put("assets",uiConfig.assets(decoration.get("assets")));
        Map<String,Object> pages=uiConfig.pages(db.config(shopId,"page_schema"));
        Map<String,Object> category=new LinkedHashMap<>(db.config(shopId,"category"));
        return ok(map("shopId",shopId,"decoration",decoration,"pages",pages,"categories",category.getOrDefault("items",Collections.emptyList())));
    }
    @GetMapping("/policies") public Object policies(@RequestParam long shopId){return ok(policies.current(shopId));}
    @GetMapping("/consents") public Object consents(HttpServletRequest req,@RequestParam long shopId){return ok(policies.consents(auth.member(req),shopId));}
    // 旧购物车缺少商品快照时，仅在当前会员的响应中补齐展示资料。
    @GetMapping("/bootstrap") public Object bootstrap(HttpServletRequest req,@RequestParam(defaultValue="2") long shopId){Actor a=auth.member(req);Map<String,Object>shop=db.one("select * from hx_shop where id=?",shopId);return ok(map("member",db.one("select id,name,phone,authorized from hx_member where id=?",a.id),"shop",map("id",shopId,"name",shop.get("name"),"kind",shop.get("kind"),"status",shop.get("status"),"returnAddress",db.config(shopId,"return_address"),"county",shop.get("county"),"categories",db.config(shopId,"category").getOrDefault("items",Collections.emptyList())),"storefront",storefrontData(shopId),"products",catalog.products(a,shopId,false),"checkinRewards",workflow.checkinStatus(a,shopId),"account",finance.account(a,shopId),"addresses",workflow.list(a,shopId,"address",false),"cart",new HexuCartReadModel(db,uiConfig.defaultAssets()).withLegacySnapshots(shopId,workflow.list(a,shopId,"cart",false)),"favorites",workflow.list(a,shopId,"favorite",false),"browseHistory",workflow.list(a,shopId,"browse_history",false),"orders",orders.list(a,shopId,false),"agent",db.agent(a.id,shopId),"binding",db.optional("select * from hx_binding where member_id=? and shop_id=?",a.id,shopId),"notifications",new HexuNotifications(db).list(a,shopId)));}
    @PostMapping("/browse/{sku}") public Object browse(HttpServletRequest req,@RequestParam long shopId,@PathVariable String sku){return ok(workflow.recordBrowse(auth.member(req),shopId,sku));}
    private Map<String,Object> storefrontData(long shopId){Map<String,Object> decoration=new LinkedHashMap<>(db.config(shopId,"decoration"));decoration.put("assets",uiConfig.assets(decoration.get("assets")));Map<String,Object> pages=uiConfig.pages(db.config(shopId,"page_schema"));Map<String,Object> category=new LinkedHashMap<>(db.config(shopId,"category"));return map("shopId",shopId,"decoration",decoration,"pages",pages,"categories",category.getOrDefault("items",Collections.emptyList()));}
    @GetMapping("/shop-access") public Object access(HttpServletRequest req,@RequestParam long shopId){return ok(catalog.access(auth.member(req),shopId));}
    @GetMapping("/groups") public Object groups(HttpServletRequest req,@RequestParam long shopId){return ok(new HexuGroupPurchase(db).list(auth.member(req),shopId));}
    @GetMapping("/groups/{id}") public Object group(HttpServletRequest req,@RequestParam long shopId,@PathVariable String id){return ok(new HexuGroupPurchase(db).detail(auth.member(req),shopId,id));}
    @GetMapping("/link-campaign") public Object linkCampaign(HttpServletRequest req,@RequestParam long shopId){return ok(new HexuLinkCampaign(db).status(auth.member(req),shopId));}
    @GetMapping("/invitation-campaign") public Object invitationCampaign(HttpServletRequest req,@RequestParam long shopId){return ok(new HexuInvitationRewards(db).status(auth.member(req),shopId));}
    @GetMapping("/marketing") public Object marketing(HttpServletRequest req,@RequestParam long shopId){return ok(workflow.marketing(auth.member(req),shopId));}
    @GetMapping("/coupons") public Object coupons(HttpServletRequest req,@RequestParam long shopId){return ok(workflow.coupons(auth.member(req),shopId));}
    @GetMapping("/products") public Object products(HttpServletRequest req,@RequestParam long shopId,@RequestParam(required=false) Long destinationShopId){return ok(catalog.products(auth.member(req),shopId,false,destinationShopId));}
    @GetMapping("/wholesale-rules") public Object wholesaleRules(HttpServletRequest req,@RequestParam long shopId,@RequestParam long destinationShopId){return ok(orders.wholesaleRules(auth.member(req),shopId,destinationShopId));}
    @GetMapping("/recipient") public Object recipient(HttpServletRequest req,@RequestParam String phone){auth.member(req);require(phone.matches("1[3-9][0-9]{9}"),"手机号格式错误");Map<String,Object> m=db.one("select id,name from hx_member where phone=? and authorized=true and status='ACTIVE'",phone);String name=str(m,"name");return ok(map("id",m.get("id"),"name",name.isEmpty()?"用户":name.substring(0,1)+"**"));}
    @GetMapping("/assessment") public Object assessment(HttpServletRequest req,@RequestParam long shopId){Actor a=auth.member(req);return ok(new HexuAssessment(db).evaluate(a.id,shopId));}
    @GetMapping("/agent-data") public Object agentData(HttpServletRequest req,@RequestParam long shopId){Actor a=auth.member(req);Map<String,Object> ag=db.agent(a.id,shopId);require(ag!=null,"您尚未取得本店代理身份");long id=number(ag,"id");return ok(map("profile",ag,"customers",db.jdbc().queryForList("select b.member_id,b.bound_at,m.name from hx_binding b join hx_member m on m.id=b.member_id where b.shop_id=? and b.agent_id=?",shopId,id),"children",db.jdbc().queryForList("select a.id,a.member_id,a.rank_no,a.status,m.name from hx_agent a join hx_member m on m.id=a.member_id where a.shop_id=? and a.parent_id=?",shopId,id)));}
    @PostMapping("/quote") public Object quote(HttpServletRequest req,@RequestBody Map<String,Object>b){return ok(orders.quote(auth.member(req),number(b,"shopId"),b));}
    @GetMapping("/orders") public Object orders(HttpServletRequest req,@RequestParam long shopId){return ok(orders.list(auth.member(req),shopId,false));}
    @GetMapping("/reviews") public Object reviews(HttpServletRequest req,@RequestParam long shopId,@RequestParam String skuId){return ok(workflow.reviews(req.getHeader("Authorization")==null?null:auth.member(req),shopId,skuId));}
    @GetMapping("/review-context") public Object reviewContext(HttpServletRequest req,@RequestParam long shopId,@RequestParam String orderId,@RequestParam String skuId,@RequestParam(required=false) String parentId){return ok(new HexuReviews(db).context(auth.member(req),shopId,orderId,skuId,parentId));}
    @GetMapping("/agent-lookup") public Object lookup(HttpServletRequest req,@RequestParam long shopId,@RequestParam String query){auth.member(req);db.shop(shopId);require(query.length()>=1&&query.length()<=80,"请填写代理姓名或编号");return ok(db.jdbc().queryForList("select a.id,a.rank_no,m.name from hx_agent a join hx_member m on m.id=a.member_id where a.shop_id=? and a.status='ACTIVE' and (m.name=? or cast(a.id as char)=?) limit 20",shopId,query,query));}
    @GetMapping("/orders/{id}") public Object order(HttpServletRequest req,@PathVariable String id){return ok(orders.detail(auth.member(req),id,false));}
    @GetMapping("/management/catalog-settings") public Object catalogSettings(HttpServletRequest req,@RequestParam long shopId){return ok(new HexuProductData(db).settings(auth.member(req),shopId));}
    @GetMapping("/management/agent-summary/{id}") public Object agentSummary(HttpServletRequest req,@PathVariable long id,@RequestParam long shopId){return ok(new HexuReadModels(db).agentSummary(auth.member(req),shopId,id));}
    @GetMapping("/earnings/{id}") public Object earning(HttpServletRequest req,@PathVariable long id){return ok(new HexuReadModels(db).earning(auth.member(req),id));}
    @GetMapping("/withdrawals/{id}") public Object withdrawal(HttpServletRequest req,@PathVariable String id){return ok(new HexuReadModels(db).withdrawal(auth.member(req),id,false));}
    @GetMapping("/management/withdrawals/{id}") public Object managementWithdrawal(HttpServletRequest req,@PathVariable String id){return ok(new HexuReadModels(db).withdrawal(auth.member(req),id,true));}
    @GetMapping("/management/reversals/{id}") public Object reversal(HttpServletRequest req,@PathVariable String id,@RequestParam long shopId){return ok(new HexuReadModels(db).reversal(auth.member(req),shopId,id));}
    @GetMapping("/account-closure") public Object closure(HttpServletRequest req){return ok(accounts.closure(auth.member(req)));}
    @GetMapping("/account") public Object account(HttpServletRequest req,@RequestParam long shopId){return ok(finance.account(auth.member(req),shopId));}
    @GetMapping("/documents/{kind}") public Object documents(HttpServletRequest req,@PathVariable String kind,@RequestParam long shopId){return ok(workflow.list(auth.member(req),shopId,kind,false));}
    @PostMapping("/commands/{operation}") public Object command(HttpServletRequest req,@PathVariable String operation,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object>b){Actor a=auth.member(req);long shop=number(b,"shopId");return ok(commands.execute(a,key,operation,b,()->{
        switch(operation){case "privacy-consent":return policies.consent(a,shop,b,"MINIPROGRAM");case "message-read":return workflow.readMessage(a,number(b,"id"));case "link-join":return workflow.joinLink(a,shop);case "order-remind":return orders.remind(a,str(b,"id"));case "account-close":return accounts.close(a,b);case "points-redeem":return orders.redeem(a,shop,b);case "checkin":return workflow.checkin(a,shop);case "bind":return catalog.bind(a,shop,str(b,"invite"));case "invite":return catalog.invite(a,shop);case "order-create":return createOrder(a,shop,b);case "order-cancel":return orders.cancel(a,str(b,"id"));case "order-receive":return orders.receive(a,str(b,"id"));case "payment-create":return orders.payment(a,str(b,"id"));case "points-transfer":return finance.transfer(a,shop,b);case "withdraw":return finance.withdraw(a,shop,b);case "return-tracking":return finance.returnTracking(a,str(b,"id"),b);case "refund-cancel":return finance.cancelRefund(a,str(b,"id"));case "exchange-receive":return finance.exchangeReceive(a,str(b,"id"));case "refund-apply":return finance.refundApply(a,str(b,"id"),b);case "document-save":return workflow.save(a,shop,str(b,"kind"),b,false);case "document-remove":return workflow.remove(a,str(b,"id"));case "coupon-claim":return workflow.claim(a,shop,str(b,"id"));default:throw new BusinessException("未知业务操作");}
    }));}
    private Object createOrder(Actor actor,long shop,Map<String,Object> body){
        if(!"WHOLESALE".equals(str(db.shop(shop),"kind")))
            require(body.get("expectedTotal") instanceof Number,"请更新客户端并重新核对订单金额");
        return orders.create(actor,shop,body);
    }
    // Merchant/mobile operators use the same tenant checks as desktop staff.
    @GetMapping("/management/statements/{id}/lines") public Object statementLines(HttpServletRequest req,@PathVariable String id,@RequestParam long shopId){return HexuAppController.ok(workflow.statementLines(auth.member(req),shopId,id));}
    @GetMapping("/management/{resource}") public Object management(HttpServletRequest req,@PathVariable String resource,@RequestParam long shopId){Actor a=auth.member(req);if(resource.startsWith("documents_"))return ok(workflow.list(a,shopId,resource.substring(10),true));if("dashboard".equals(resource))return ok(workflow.dashboard(a,shopId));if("products".equals(resource))return ok(catalog.products(a,shopId,true));if("orders".equals(resource))return ok(orders.list(a,shopId,true));return ok(workflow.managementList(a,shopId,resource));}
    @GetMapping("/management/orders/{id}") public Object managementOrder(HttpServletRequest req,@PathVariable String id,@RequestParam long shopId){Actor a=auth.member(req);db.manage(a,shopId,"ORDER","WAREHOUSE","FINANCE","SUPPORT");Map<String,Object> order=orders.detail(a,id,true);require(number(order,"shop_id")==shopId,"订单不属于当前商城");return ok(order);}
    @PostMapping("/management/{operation}") public Object manageCommand(HttpServletRequest req,@PathVariable String operation,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object>b){Actor a=auth.member(req);return ok(commands.execute(a,key,"manage/"+operation,b,()->HexuAdminController.dispatch(a,operation,b,catalog,orders,finance,workflow)));}
}
