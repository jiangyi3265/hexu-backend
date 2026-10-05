package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.util.Collections;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Migration seed for the existing 128-page canvas.  It is only used when a
 * tenant has not published page_schema yet; the admin document immediately
 * takes precedence once saved.  Keeping the seed on the server makes the
 * initial mini-program render come from an HTTP response instead of bundled
 * business data, while preserving the current UI byte-for-byte.
 */
@Service
public class HexuUiConfigService {
    private final Map<String,Object> seed;
    public HexuUiConfigService(ObjectMapper mapper){
        Map<String,Object> parsed=new LinkedHashMap<>();
        try(InputStream input=new ClassPathResource("hexu-ui-seed.json").getInputStream()){
            parsed=mapper.readValue(input,Map.class);
        }catch(Exception ignored){
            // The endpoint remains usable if a stripped production package
            // omits the migration seed; admins can publish page_schema later.
        }
        seed=parsed;
    }
    public Map<String,Object> pages(Map<String,Object> published){
        if(published!=null&&!published.isEmpty())return correctLegacyCrossShopAction(published);
        Object screens=seed.get("screens"),catalog=seed.get("catalog");
        Map<String,Object> fallback=screens==null&&catalog==null?Collections.emptyMap():new LinkedHashMap<String,Object>(){{
            if(screens!=null)put("screens",screens);
            if(catalog!=null)put("catalog",catalog);
            put("schemaVersion",seed.getOrDefault("schemaVersion",1));
        }};
        return correctLegacyCrossShopAction(fallback);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> correctLegacyCrossShopAction(Map<String,Object> pages){
        Object screensValue=pages.get("screens");if(!(screensValue instanceof Map))return pages;
        Map<String,Object> screens=(Map<String,Object>)screensValue;
        Object pageValue=screens.get("M43");if(!(pageValue instanceof Map))return pages;
        Map<String,Object> page=(Map<String,Object>)pageValue;
        Object footerValue=page.get("footer");if(!(footerValue instanceof List))return pages;
        List<?> footer=(List<?>)footerValue;List<Object> corrected=new ArrayList<>(footer);boolean changed=false;
        for(int index=0;index<footer.size();index++)if(footer.get(index) instanceof Map){
            Map<String,Object> action=(Map<String,Object>)footer.get(index);
            if("继续普通购买".equals(action.get("label"))&&"M05".equals(action.get("target"))){
                Map<String,Object> replacement=new LinkedHashMap<>(action);replacement.put("label","申请跨店采购");replacement.put("target","M44");corrected.set(index,replacement);changed=true;
            }
        }
        Object blocksValue=page.get("blocks");List<Object> correctedBlocks=null;
        if(blocksValue instanceof List){
            List<?> blocks=(List<?>)blocksValue;correctedBlocks=new ArrayList<>(blocks);
            for(int index=0;index<blocks.size();index++)if(blocks.get(index) instanceof Map){
                Map<String,Object> block=(Map<String,Object>)blocks.get(index);
                if("收益规则".equals(block.get("title"))&&"访问商城按普通客户处理，不自动向原商城代理链跨商户分佣。".equals(block.get("body"))){
                    Map<String,Object> replacement=new LinkedHashMap<>(block);
                    replacement.put("body","跨店采购需目标商城审核；获批后按授权商品和数量购买，不迁移原代理归属，也不自动跨商户分佣。");
                    correctedBlocks.set(index,replacement);changed=true;
                }
            }
        }
        if(!changed)return pages;
        Map<String,Object> copy=new LinkedHashMap<>(pages),updatedScreens=new LinkedHashMap<>(screens),updatedPage=new LinkedHashMap<>(page);
        updatedPage.put("footer",corrected);if(correctedBlocks!=null)updatedPage.put("blocks",correctedBlocks);
        updatedScreens.put("M43",updatedPage);copy.put("screens",updatedScreens);return copy;
    }
    public Map<String,Object> defaultAssets(){
        Map<String,Object> assets=new LinkedHashMap<>();
        for(String name:Arrays.asList("forest","loginScene","hero","tea","stapler","whiteStapler","greenStapler","pinkStapler","product","agentProduct","cup","paper","oil","sofa","lamp","avatar","towel","umbrella","gift","support","success","growth","shopfront","skincare","qr","server"))assets.put(name,"/hexu/app/ui-assets/"+name);
        return assets;
    }
    public Map<String,Object> assets(Object published){
        Map<String,Object> assets=defaultAssets();
        if(published instanceof Map)assets.putAll((Map<String,Object>)published);
        // Invalidate the old 30-day crop cache without changing merchant-uploaded images.
        for(String name:Arrays.asList("hero","tea")){
            String legacyUrl="/hexu/app/ui-assets/"+name;
            if(legacyUrl.equals(assets.get(name)))assets.put(name,legacyUrl+"?v=2");
        }
        return assets;
    }
}
