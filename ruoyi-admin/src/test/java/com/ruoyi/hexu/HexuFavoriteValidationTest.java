package com.ruoyi.hexu;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class HexuFavoriteValidationTest {
    @Test void favoriteIdsMustBeUniqueStrings(){
        assertEquals(Arrays.asList("cup","paper"),HexuWorkflowService.checkedFavoriteIds(Arrays.asList("cup","paper")));
        assertEquals(Collections.emptyList(),HexuWorkflowService.checkedFavoriteIds(Collections.emptyList()));
        for(Object invalid:Arrays.asList(null,Collections.singletonMap("id","cup"),Arrays.asList("cup",Collections.singletonMap("type","click")),Arrays.asList("cup","cup"),Collections.singletonList(" "),Collections.singletonList("cup\n"))){
            assertThrows(BusinessException.class,()->HexuWorkflowService.checkedFavoriteIds(invalid));
        }
    }
}
