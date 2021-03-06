package one.rewind.android.automator.account;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import one.rewind.db.Daos;
import one.rewind.db.annotation.DBName;
import one.rewind.db.model.ModelL;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author maxuefeng [m17793873123@163.com]
 */
@DBName("android_automator")
@DatabaseTable(tableName = "accounts")
public class Account extends ModelL {

    static {
        // 定时维护账户的状态
        accountManager();
    }

    private static final Logger logger = LogManager.getLogger(Account.class.getName());

    // 搜索公众号限流 账号冻结时间间隔
    public static long Default_Search_Public_Account_Frozen_Time = 72 * 3600 * 1000;

    // 点击后出现 "全部消息过于频繁" 账号冻结时间间隔
    public static long Default_Get_Public_Account_Essay_List_Frozen_Time = 24 * 3600 * 1000;

    //
    @DatabaseField(dataType = DataType.STRING, width = 32)
    public String src_id;

    // 用户名
    @DatabaseField(dataType = DataType.STRING, width = 32)
    public String username;

    // 关联电话号码
    @DatabaseField(dataType = DataType.STRING, width = 32)
    public String mobile;

    // 密码
    @DatabaseField(dataType = DataType.STRING, width = 32)
    public String password;

    // 当前对应设备机器码
    @DatabaseField(dataType = DataType.STRING, width = 32, indexName = "udid-adapter-status")
    public String udid;

    // 账号类型
    @DatabaseField(dataType = DataType.STRING, width = 64, indexName = "udid-adapter-status")
    public String adapter_class_name;

    // 账号状态
    @DatabaseField(dataType = DataType.ENUM_STRING, width = 64, indexName = "udid-adapter-status")
    public Status status = Status.Normal;

    @DatabaseField(dataType = DataType.BOOLEAN)
    public boolean occupied = false;

    /**
     * 账号状态
     * TODO 微信公众号的状态应该使用继承实现
     */
    public static enum Status {

        Normal,   // 正常状态
        Broken,   // 用户名无效 或密码无效
        Blocked,  // 账号被封
        Search_Public_Account_Frozen, // 查询公众号被限流
        Get_Public_Account_Essay_List_Frozen // 获取公众号历史文章被限流
    }

    // TODO 构造方法

    /**
     * 获取可用的账号
     *
     * @param udid
     * @param adapter_class_name
     * @return
     * @throws Exception
     */
    public static synchronized Account getAccount(String udid, String adapter_class_name) {

        long t = System.currentTimeMillis();
        Account account = null;

        try {
            Dao<Account, String> dao = Daos.get(Account.class);

            account = dao.queryBuilder().where().
                    eq("udid", udid).and().
                    eq("adapter_class_name", adapter_class_name).
                    and().
                    eq("status", Status.Normal)
                    .and().eq("occupied", 0)
                    .queryForFirst();

            if (account != null) {
                account.occupied = true;
                account.update();
            }
        } catch (Exception e) {
            logger.error("Error get account, ", e);
        }

        return account;
    }

    /**
     * @param udid
     * @param adapter_class_name
     * @param statuses
     * @return
     */
    public static synchronized Account getAccount(String udid, String adapter_class_name, List<Status> statuses) {

        Account account = null;

        try {
            Dao<Account, String> dao = Daos.get(Account.class);

            account = dao.queryBuilder().where()
                    .eq("udid", udid)
                    .and().eq("adapter_class_name", adapter_class_name)
                    .and().in("status", statuses)
                    .and().eq("occupied", 0)
                    .queryForFirst();

            if (account != null) {
                account.occupied = true;
                account.update();
            }

        } catch (Exception e) {
            logger.error("Error get account, ", e);
        }

        return account;
    }

    /**
     * 管理账号的状态  每隔一小时去数据库中修改账号的状态
     */
    public static void accountManager() {
        Timer timer = new Timer("account-status-manager");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {

                    long t = System.currentTimeMillis();

                    Dao<Account, String> accountDao = Daos.get(Account.class);

                    List<Account> accounts = accountDao.queryBuilder().where()
                            .eq("status", Status.Get_Public_Account_Essay_List_Frozen)
                            .and()
                            .le("update_time", new Date(t - Default_Get_Public_Account_Essay_List_Frozen_Time))
                            .query();

                    List<Account> accounts2 = accountDao.queryBuilder().where()
                            .eq("status", Status.Search_Public_Account_Frozen)
                            .and()
                            .le("update_time", new Date(t - Default_Search_Public_Account_Frozen_Time))
                            .query();

                    accounts.addAll(accounts2);

                    accounts.forEach(a -> {
                        try {
                            a.update_time = new Date();
                            a.status = Status.Normal;
                            a.update();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 60 * 1000);
    }
}
