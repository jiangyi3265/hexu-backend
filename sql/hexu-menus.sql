INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) VALUES
(5000,'分销批发中台',0,1,'hexu',NULL,1,0,'M','0','0','','shopping','admin',NOW()),
(5001,'经营工作台',5000,1,'overview','hexu/overview/index',1,0,'C','0','0','hexu:dashboard:view','dashboard','admin',NOW()),
(5002,'商品与库存',5000,2,'products','hexu/products/index',1,0,'C','0','0','hexu:products:view','shopping','admin',NOW()),
(5003,'订单与履约',5000,3,'orders','hexu/orders/index',1,0,'C','0','0','hexu:orders:view','list','admin',NOW()),
(5004,'售后工作台',5000,4,'refunds','hexu/refunds/index',1,0,'C','0','0','hexu:refunds:view','form','admin',NOW()),
(5005,'资金与结算',5000,5,'finance','hexu/finance/index',1,0,'C','0','0','hexu:finance:view','money','admin',NOW()),
(5006,'代理与客户',5000,6,'agents','hexu/agents/index',1,0,'C','0','0','hexu:agents:view','peoples','admin',NOW()),
(5007,'业务申请审核',5000,7,'applications','hexu/applications/index',1,0,'C','0','0','hexu:applications:view','form','admin',NOW()),
(5008,'营销与配置',5000,8,'settings','hexu/settings/index',1,0,'C','0','0','hexu:settings:view','system','admin',NOW()),
(5009,'操作与账务审计',5000,9,'audit','hexu/audit/index',1,0,'C','0','0','hexu:audit:view','log','admin',NOW());

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) SELECT 5010,'经营分析报表',5000,10,'reports','hexu/reports/index',1,0,'C','0','0','hexu:reports:view','chart','admin',NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=5010);
