package com.ruoyi.system;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysUserRole;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.impl.SysRoleServiceImpl;
import com.ruoyi.web.controller.system.SysRoleController;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.*;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=SysRoleBulkAuthorizationTest.Config.class)
class SysRoleBulkAuthorizationTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:sysrolebulktest;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
        @Bean org.springframework.transaction.PlatformTransactionManager tx(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean SqlSessionFactory sqlSessions(DataSource source)throws Exception{
            SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setTypeAliases(SysUserRole.class);factory.setMapperLocations(new ClassPathResource("mapper/system/SysUserRoleMapper.xml"));return factory.getObject();
        }
        @Bean SysUserRoleMapper userRoles(SqlSessionFactory factory){return new SqlSessionTemplate(factory).getMapper(SysUserRoleMapper.class);}
        @Bean SysRoleMapper roles(){return mock(SysRoleMapper.class);}
        @Bean SysRoleMenuMapper roleMenus(){return mock(SysRoleMenuMapper.class);}
        @Bean SysRoleDeptMapper roleDepts(){return mock(SysRoleDeptMapper.class);}
        @Bean ISysRoleService service(){return new SysRoleServiceImpl();}
    }
    @Autowired JdbcTemplate jdbc;@Autowired ISysRoleService service;
    @BeforeEach void prepare(){
        jdbc.execute("DROP ALL OBJECTS");jdbc.execute("CREATE TABLE sys_user_role(user_id BIGINT NOT NULL,role_id BIGINT NOT NULL,PRIMARY KEY(user_id,role_id))");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(10,2),(10,3),(11,3)");
    }
    List<Map<String,Object>> rows(){return jdbc.queryForList("SELECT user_id,role_id FROM sys_user_role ORDER BY user_id,role_id");}
    @Test void serviceRejectsNullAndEmptyArraysWithClearErrorsBeforeMapperWrite(){
        List<Map<String,Object>> before=rows();
        for(Long[] users:Arrays.asList((Long[])null,new Long[0])){
            assertEquals("请至少选择一位用户",assertThrows(ServiceException.class,()->service.insertAuthUsers(2L,users)).getMessage());
            assertEquals("请至少选择一位用户",assertThrows(ServiceException.class,()->service.deleteAuthUsers(2L,users)).getMessage());
        }
        assertEquals(before,rows());
    }
    @Test void invalidRolesNullElementsNonpositiveAndDuplicateUsersRejectWithoutPartialChanges(){
        List<Map<String,Object>> before=rows();
        for(Long role:Arrays.asList(null,0L,-1L)){
            assertEquals("请选择有效角色",assertThrows(ServiceException.class,()->service.insertAuthUsers(role,new Long[]{12L})).getMessage());
            assertEquals("请选择有效角色",assertThrows(ServiceException.class,()->service.deleteAuthUsers(role,new Long[]{10L})).getMessage());
        }
        for(Long[] users:Arrays.asList(new Long[]{12L,null},new Long[]{12L,0L},new Long[]{12L,-1L},new Long[]{12L,12L})){
            assertThrows(ServiceException.class,()->service.insertAuthUsers(2L,users));assertThrows(ServiceException.class,()->service.deleteAuthUsers(2L,users));
        }
        assertEquals(before,rows());
    }
    @Test void singletonGrantAndCancelUseRealMapperAndPreserveOtherRoles(){
        assertEquals(1,service.insertAuthUsers(2L,new Long[]{12L}));assertEquals(1,service.deleteAuthUsers(2L,new Long[]{10L}));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=10 AND role_id=3",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=12 AND role_id=2",Integer.class));
        assertEquals(0,service.deleteAuthUsers(2L,new Long[]{99L}));
    }
    @Test void multipleUsersGenerateValidForeachSqlAndCancelOnlySelectedRole(){
        assertEquals(2,service.insertAuthUsers(2L,new Long[]{12L,13L}));assertEquals(2,service.deleteAuthUsers(2L,new Long[]{12L,13L}));assertEquals(3,rows().size());
    }
    @Test void alreadyLinkedUserFailsClearlyAndWholeGrantTransactionRollsBack(){
        List<Map<String,Object>> before=rows();
        ServiceException error=assertThrows(ServiceException.class,()->service.insertAuthUsers(2L,new Long[]{12L,10L,13L}));
        assertEquals("部分用户已关联该角色，请刷新列表后重试",error.getMessage());assertEquals(before,rows());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id IN (12,13)",Integer.class));
    }
    @Test void unrelatedDataAccessErrorsAreNotMisreportedAsDuplicateAssociation(){
        SysUserRoleMapper mapper=mock(SysUserRoleMapper.class);SysRoleServiceImpl direct=new SysRoleServiceImpl();ReflectionTestUtils.setField(direct,"userRoleMapper",mapper);
        BadSqlGrammarException error=new BadSqlGrammarException("test","invalid",new java.sql.SQLException("测试非重复键错误"));when(mapper.batchUserRole(anyList())).thenThrow(error);
        assertSame(error,assertThrows(BadSqlGrammarException.class,()->direct.insertAuthUsers(2L,new Long[]{12L})));
    }
    @Test void controllerRejectsMissingSelectionAndPreservesPermissionAndScopeCalls()throws Exception{
        ISysRoleService roles=mock(ISysRoleService.class);SysRoleController controller=new SysRoleController();ReflectionTestUtils.setField(controller,"roleService",roles);
        for(Long[] users:Arrays.asList((Long[])null,new Long[0])){
            assertEquals("请至少选择一位用户",assertThrows(ServiceException.class,()->controller.selectAuthUserAll(2L,users)).getMessage());
            assertEquals("请至少选择一位用户",assertThrows(ServiceException.class,()->controller.cancelAuthUserAll(2L,users)).getMessage());
        }
        assertThrows(ServiceException.class,()->controller.selectAuthUserAll(null,new Long[]{12L}));assertThrows(ServiceException.class,()->controller.cancelAuthUserAll(0L,new Long[]{10L}));verifyNoInteractions(roles);
        Long[] users={12L};when(roles.insertAuthUsers(2L,users)).thenReturn(1);when(roles.deleteAuthUsers(2L,users)).thenReturn(1);
        assertEquals(200,controller.selectAuthUserAll(2L,users).get("code"));assertEquals(200,controller.cancelAuthUserAll(2L,users).get("code"));
        verify(roles).checkRoleDataScope(2L);verify(roles).insertAuthUsers(2L,users);verify(roles).deleteAuthUsers(2L,users);
        for(String method:Arrays.asList("selectAuthUserAll","cancelAuthUserAll"))assertEquals("@ss.hasPermi('system:role:edit')",SysRoleController.class.getMethod(method,Long.class,Long[].class).getAnnotation(PreAuthorize.class).value());
    }
}
