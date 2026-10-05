package com.ruoyi.hexu;

import com.ruoyi.common.annotation.Anonymous;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static com.ruoyi.hexu.BusinessException.require;

/** Serves the existing sprite crops through the backend during migration. */
@Anonymous @RestController @RequestMapping("/hexu/app/ui-assets")
public class HexuUiAssetController {
    private static final Map<String,int[]> CROPS=new HashMap<>();
    static{
        put("forest","01",288,174,77,64);put("loginScene","01",50,169,320,172);
        // Stay inside the original photos; exclude the canvas's white margin and rounded corners.
        put("hero","01",584,278,146,129);put("tea","01",947,284,154,124);
        put("stapler","02",1180,273,92,106);put("whiteStapler","02",883,371,55,56);put("greenStapler","02",961,371,64,56);put("pinkStapler","02",1041,371,55,56);put("product","02",55,325,290,126);put("agentProduct","02",431,375,298,204);
        put("cup","01",435,574,141,129);put("paper","01",804,591,140,137);put("oil","01",966,591,146,137);put("sofa","01",594,574,144,132);
        put("lamp","14",1207,342,68,125);put("avatar","01",171,358,85,83);put("towel","14",589,393,142,105);put("umbrella","14",433,640,137,96);
        put("gift","15",201,298,160,81);put("support","08",215,182,150,133);put("success","08",1166,188,319,178);put("growth","10",622,215,111,98);
        put("shopfront","10",1321,210,160,139);put("skincare","30",211,291,155,140);put("qr","15",675,387,65,77);put("server","32",972,220,140,99);
    }
    private static void put(String key,String board,int x,int y,int w,int h){CROPS.put(key,new int[]{Integer.parseInt(board),x,y,w,h});}
    @GetMapping("/{name}") public ResponseEntity<byte[]> read(@PathVariable String name)throws Exception{
        int[] crop=CROPS.get(name);require(crop!=null,"图片资源不存在");
        BufferedImage source=ImageIO.read(new ClassPathResource("static/hexu/design/board"+String.format("%02d",crop[0])+".png").getInputStream());
        require(crop[1]>=0&&crop[2]>=0&&crop[1]+crop[3]<=source.getWidth()&&crop[2]+crop[4]<=source.getHeight(),"图片裁剪区域无效");
        BufferedImage result=source.getSubimage(crop[1],crop[2],crop[3],crop[4]);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(result,"png",out);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30))).header("X-Content-Type-Options","nosniff").body(out.toByteArray());
    }
}
