package com.ruoyi.hexu;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.slf4j.LoggerFactory;
@Configuration @EnableScheduling
public class HexuJobs {
 private final HexuOrderService orders;private final HexuAgencyService agency;private final HexuWorkflowService workflow;
 public HexuJobs(HexuOrderService orders,HexuAgencyService agency,HexuWorkflowService workflow){this.orders=orders;this.agency=agency;this.workflow=workflow;}
 @Scheduled(fixedDelay=60000,initialDelay=60000)
 public void expire(){try{orders.expireGroups();orders.expire();}catch(Exception e){LoggerFactory.getLogger(HexuJobs.class).error("Order expiry failed; will retry next minute",e);}}
 @Scheduled(fixedDelay=60000,initialDelay=30000)
 public void migrations(){for(java.util.Map<String,Object>d:workflow.dueShopTransfers())try{workflow.applyShopTransfer(HexuStore.str(d,"id"));}catch(RuntimeException e){workflow.deferScheduledReview(HexuStore.str(d,"id"),e.getMessage());LoggerFactory.getLogger(HexuJobs.class).error("Approved shop handover requires updated manifest: {}",d.get("id"),e);}try{agency.applyDue();workflow.applyScheduledMigrations();workflow.applyScheduledPromotions();}catch(Exception e){LoggerFactory.getLogger(HexuJobs.class).error("Approved migration needs review; no partial changes committed",e);}}
 @Scheduled(fixedDelay=60000,initialDelay=45000)
 public void receive(){for(java.util.Map<String,Object> o:orders.dueReceipts())try{orders.receive(new Actor(HexuStore.number(o,"buyer_id"),false,false),HexuStore.str(o,"id"));}catch(BusinessException e){LoggerFactory.getLogger(HexuJobs.class).info("Auto-receive deferred for {}: {}",o.get("id"),e.getMessage());}}

 @Scheduled(fixedDelay=3600000,initialDelay=90000) public void downgrades(){for(java.util.Map<String,Object>s:workflow.downgradeShops())try{workflow.runDowngrades(new Actor(0,true,true),HexuStore.number(s,"shop_id"));}catch(RuntimeException e){LoggerFactory.getLogger(HexuJobs.class).error("Assessment period processing failed",e);}for(java.util.Map<String,Object>d:workflow.dueDowngrades())try{workflow.applyDowngrade(HexuStore.str(d,"id"));}catch(RuntimeException e){workflow.deferScheduledReview(HexuStore.str(d,"id"),e.getMessage());LoggerFactory.getLogger(HexuJobs.class).error("Approved downgrade awaits disposition: {}",d.get("id"),e);}}

 @Scheduled(fixedDelay=5000,initialDelay=10000) public void reconciliation(){for(java.util.Map<String,Object>b:workflow.reconciliationQueue())try{workflow.processStatement(HexuStore.str(b,"id"));}catch(RuntimeException e){LoggerFactory.getLogger(HexuJobs.class).error("Statement comparison failed; retrying next cycle: {}",b.get("id"),e);}}
 @Scheduled(fixedDelay=2000,initialDelay=15000) public void reports(){for(java.util.Map<String,Object>j:workflow.reportQueue())try{workflow.processReport(HexuStore.str(j,"id"));}catch(RuntimeException e){workflow.failReport(HexuStore.str(j,"id"),e.getMessage());LoggerFactory.getLogger(HexuJobs.class).error("Report snapshot failed: {}",j.get("id"),e);}}
}
