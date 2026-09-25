package com.ruoyi.hexu;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import javax.annotation.PreDestroy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
/** Integration backups are encrypted; restore is restricted to a new verification database. */
@Profile("hexu-dev") @RestController @RequestMapping("/hexu/admin/backups")
public class HexuBackupController {
 private final HexuIdentity identity;private final HexuStore db;private final HexuCommands commands;
 private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"hexu-backup");t.setDaemon(true);return t;});
 private final AtomicBoolean running=new AtomicBoolean(false);private volatile String error="",job="";
 @Value("${hexu.backup.enabled:false}") private boolean enabled;
 @Value("${hexu.backup.node:node}") private String node;
 public HexuBackupController(HexuIdentity identity,HexuStore db,HexuCommands commands){this.identity=identity;this.db=db;this.commands=commands;}
 private Path runtime(){return Paths.get(".hexu-local").toAbsolutePath().normalize();}
 @GetMapping public Object status(){db.platform(identity.admin());Map<String,Object>record=new LinkedHashMap<>();try{Path file=runtime().resolve("last-backup.json");if(Files.exists(file))record=db.parse(Files.readString(file));}catch(Exception e){throw new BusinessException("读取备份状态失败");}boolean exists=!record.isEmpty();if(record.containsKey("file"))record.put("file",Paths.get(str(record,"file")).getFileName().toString());record.put("id",job.isEmpty()?"BACKUP":job);record.put("status",running.get()?"RUNNING":!error.isEmpty()?"FAILED":exists?"SUCCESS":"NONE");record.put("error",error);record.put("scheduled",enabled);record.put("schedule","每日03:00（北京时间，需后台运行）");return map("code",200,"data",Collections.singletonList(record));}
 @PostMapping public Object backup(@RequestHeader("Idempotency-Key")String key){Actor actor=identity.admin();db.platform(actor);return map("code",200,"data",commands.execute(actor,key,"DATABASE_BACKUP",Collections.emptyMap(),()->start(actor)));}
 private Map<String,Object> start(Actor actor){require(running.compareAndSet(false,true),"已有备份任务正在执行");job=id("BAK");String current=job;error="";executor.submit(()->{Process process=null;try{Path script=Paths.get("scripts","backup-database.mjs").toAbsolutePath().normalize();require(Files.isRegularFile(script),"备份脚本不存在");Files.createDirectories(runtime());db.audit(actor,0,"BACKUP_START",current,null,null);process=new ProcessBuilder(node,script.toString(),"backup").redirectErrorStream(true).redirectOutput(runtime().resolve("backup-task.log").toFile()).start();require(process.waitFor(180,TimeUnit.SECONDS),"备份超时");require(process.exitValue()==0,"备份失败，请检查本机备份日志");db.audit(actor,0,"BACKUP_SUCCESS",current,null,map("encrypted",true));}catch(Exception e){error=e.getMessage()==null?"备份任务失败":e.getMessage();db.audit(actor,0,"BACKUP_FAILED",current,null,map("error",error));}finally{if(process!=null&&process.isAlive())process.destroyForcibly();running.set(false);}});return map("id",current,"status","RUNNING");}
 @Scheduled(cron="${hexu.backup.cron:0 0 3 * * *}",zone="Asia/Shanghai") public void daily(){if(enabled&&!running.get())start(new Actor(0,true,true));}
 @PreDestroy public void close(){executor.shutdownNow();}
}
