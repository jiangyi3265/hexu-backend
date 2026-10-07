package com.ruoyi.hexu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** Province, city and county relationships used by both address and profile saves. */
final class HexuRegions {
    private static final Set<String> PATHS=load();
    private HexuRegions(){}

    static boolean valid(String value){
        if(value==null||value.trim().isEmpty()||value.codePoints().anyMatch(Character::isISOControl))return false;
        return valid(Arrays.asList(value.trim().split("\\s+")));
    }

    static boolean valid(List<String> names){
        return names!=null&&(names.size()==2||names.size()==3)
            &&names.stream().allMatch(name->name!=null&&!name.isEmpty()&&!name.contains(" "))
            &&PATHS.contains(String.join(" ",names));
    }

    static String canonical(String value){return String.join(" ",value.trim().split("\\s+"));}

    static List<String> options(String query){
        String term=query==null?"":query.trim();
        if(term.length()<2)return Collections.emptyList();
        List<String> matches=new ArrayList<>();
        for(String path:PATHS)if(path.contains(term))matches.add(path);
        Collections.sort(matches);
        return matches.size()>50?matches.subList(0,50):matches;
    }

    private static Set<String> load(){
        // @vant/area-data 2.2.0, MIT; see hexu-regions-LICENSE.txt.
        try(InputStream input=HexuRegions.class.getResourceAsStream("/hexu-regions.json")){
            if(input==null)throw new IllegalStateException("地区数据文件缺失");
            Map<String,Map<String,String>> data=new ObjectMapper().readValue(input,new TypeReference<Map<String,Map<String,String>>>(){});
            Map<String,String> provinces=data.get("province_list"),cities=data.get("city_list"),counties=data.get("county_list");
            Set<String> paths=new HashSet<>();
            for(Map.Entry<String,String> province:provinces.entrySet())for(Map.Entry<String,String> city:cities.entrySet()){
                if(!city.getKey().startsWith(province.getKey().substring(0,2)))continue;
                paths.add(province.getValue()+" "+city.getValue());
                for(Map.Entry<String,String> county:counties.entrySet()){
                    if(!county.getKey().startsWith(city.getKey().substring(0,4))||Arrays.asList("其它区","其他区","市辖区").contains(county.getValue()))continue;
                    paths.add(province.getValue()+" "+city.getValue()+" "+county.getValue());
                    if(city.getValue().equals(province.getValue()))paths.add(province.getValue()+" "+county.getValue());
                }
            }
            return Collections.unmodifiableSet(paths);
        }catch(IOException e){throw new IllegalStateException("地区数据文件无法读取",e);}
    }
}
