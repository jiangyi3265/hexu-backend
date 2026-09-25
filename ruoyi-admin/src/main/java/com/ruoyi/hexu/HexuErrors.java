package com.ruoyi.hexu;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice(basePackages="com.ruoyi.hexu")
public class HexuErrors {
    @ExceptionHandler(BusinessException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,Object> business(BusinessException e){return HexuStore.map("code",400,"msg",e.getMessage());}
    @ExceptionHandler({ArithmeticException.class,IllegalArgumentException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,Object> invalid(Exception e){return HexuStore.map("code",400,"msg","参数格式或数值范围错误");}
}
