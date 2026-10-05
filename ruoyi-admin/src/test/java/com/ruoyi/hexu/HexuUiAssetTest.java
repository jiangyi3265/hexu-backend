package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HexuUiAssetTest {
    @Test
    void bannerCropsExcludeCanvasWhiteBorder() throws Exception {
        HexuUiAssetController controller=new HexuUiAssetController();
        for(String name:new String[]{"hero","tea"}){
            ResponseEntity<byte[]> response=controller.read(name);
            assertEquals(200,response.getStatusCodeValue());
            BufferedImage image=ImageIO.read(new ByteArrayInputStream(response.getBody()));
            assertEquals("hero".equals(name)?146:154,image.getWidth());
            assertEquals("hero".equals(name)?129:124,image.getHeight());
            for(int y=0;y<image.getHeight();y++){
                Color edge=new Color(image.getRGB(image.getWidth()-1,y));
                assertFalse(edge.getRed()>=230&&edge.getGreen()>=230&&edge.getBlue()>=230,
                    name+" contains the white canvas at its right edge, row "+y);
            }
        }
    }

    @Test
    void publishedAssetsRefreshLegacyCacheWithoutOverwritingCustomImages(){
        HexuUiConfigService config=new HexuUiConfigService(new ObjectMapper());
        Map<String,Object> published=new LinkedHashMap<>();
        published.put("tea","/hexu/app/ui-assets/tea");
        published.put("hero","https://merchant.example/banner.png");
        Map<String,Object> resolved=config.assets(published);
        assertEquals("/hexu/app/ui-assets/tea?v=2",resolved.get("tea"));
        assertEquals(published.get("hero"),resolved.get("hero"));
        assertEquals("/hexu/app/ui-assets/tea",published.get("tea"));
        assertEquals("/hexu/app/ui-assets/hero?v=2",config.assets(null).get("hero"));
        assertEquals("/hexu/app/ui-assets/cup",resolved.get("cup"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyCrossShopCopyMatchesTheApprovalFlowWithoutOverwritingPublishedChoices(){
        HexuUiConfigService config=new HexuUiConfigService(new ObjectMapper());
        Map<String,Object> action=new LinkedHashMap<>();action.put("label","继续普通购买");action.put("target","M05");
        Map<String,Object> notice=new LinkedHashMap<>();notice.put("title","收益规则");notice.put("body","访问商城按普通客户处理，不自动向原商城代理链跨商户分佣。");
        Map<String,Object> page=new LinkedHashMap<>();page.put("footer",Arrays.asList(action));page.put("blocks",Arrays.asList(notice));
        Map<String,Object> screens=new LinkedHashMap<>();screens.put("M43",page);
        Map<String,Object> published=new LinkedHashMap<>();published.put("screens",screens);
        Map<String,Object> corrected=(Map<String,Object>)((Map<String,Object>)config.pages(published).get("screens")).get("M43");
        Map<String,Object> button=(Map<String,Object>)((List<?>)corrected.get("footer")).get(0);
        assertEquals("申请跨店采购",button.get("label"));assertEquals("M44",button.get("target"));
        assertTrue(((Map<?,?>)((List<?>)corrected.get("blocks")).get(0)).get("body").toString().contains("需目标商城审核"));
        assertEquals("继续普通购买",action.get("label"));assertEquals("M05",action.get("target"));
        Map<String,Object> custom=new LinkedHashMap<>(action);custom.put("label","自定义访问规则");
        page.put("footer",Arrays.asList(custom));notice.put("body","商家自定义规则");assertSame(published,config.pages(published));
    }
}
