package com.ruoyi.hexu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HexuReviewNotificationTest {
    @Test void reviewTitlesAreChineseAndKeepEventClassification(){
        String[] decisions={"APPROVED","REJECTED","SUPPLEMENT"};
        String[][] titles={{"商品评价审核通过","商品评价未通过审核","商品评价待补充"},{"商品追评审核通过","商品追评未通过审核","商品追评待补充"},{"客服留言已处理","客服留言未通过审核","客服留言待补充"}};
        String[] kinds={"review","review_append","support"};
        for(int kind=0;kind<kinds.length;kind++)for(int decision=0;decision<decisions.length;decision++){
            String title=HexuWorkflowService.reviewNotificationTitle(kinds[kind],decisions[decision]);assertEquals(titles[kind][decision],title);assertEquals("SYSTEM",HexuNotifications.classify(title));
            assertFalse(title.contains(decisions[decision]));
        }
        String[] applicationTitles={"申请处理结果：审核通过","申请处理结果：审核未通过","申请处理结果：待补充资料"};
        for(int decision=0;decision<decisions.length;decision++){
            String title=HexuWorkflowService.reviewNotificationTitle("agent_application",decisions[decision]);
            assertEquals(applicationTitles[decision],title);
            assertEquals("AGENT_UPDATED",HexuNotifications.classify(title));
        }
        assertEquals("AGENT_UPDATED",HexuNotifications.classify(HexuWorkflowService.reviewNotificationTitle("promotion","SCHEDULED")));
    }
}
