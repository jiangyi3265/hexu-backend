package com.ruoyi.hexu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HexuReportExportDisplayTest {
    @Test void exportUsesReportPageLabelsWithoutChangingOtherValues() {
        assertEquals("云代理", HexuReportController.displayCell("rank_no", 1));
        assertEquals("分货中心", HexuReportController.displayCell("rank_no", 2));
        assertEquals("总代理", HexuReportController.displayCell("rank_no", 3));
        assertEquals("未记录职级", HexuReportController.displayCell("rank_no", 0));
        assertEquals(9, HexuReportController.displayCell("rank_no", 9));
        assertEquals("已发货", HexuReportController.displayCell("order_status", "SHIPPED"));
        assertEquals("已退款", HexuReportController.displayCell("order_status", "REFUNDED"));
        assertEquals("暂无销量", HexuReportController.displayCell("turnoverDays", null));
        assertEquals(3.25, HexuReportController.displayCell("turnoverDays", 3.25));
        assertEquals("cup", HexuReportController.displayCell("sku_id", "cup"));
    }
}
