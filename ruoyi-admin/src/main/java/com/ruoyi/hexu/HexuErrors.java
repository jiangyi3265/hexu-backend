package com.ruoyi.hexu;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice(basePackages="com.ruoyi.hexu")
public class HexuErrors {
    @ExceptionHandler(HexuMemberAuthException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String,Object> memberAuth(HexuMemberAuthException e){return HexuStore.map("code",401,"msg",e.getMessage());}
    @ExceptionHandler(BusinessException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,Object> business(BusinessException e){return HexuStore.map("code",400,"msg",e.getMessage());}
    @ExceptionHandler({ArithmeticException.class,IllegalArgumentException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,Object> invalid(Exception e){return HexuStore.map("code",400,"msg","参数格式或数值范围错误");}
}
