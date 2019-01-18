package one.rewind.android.automator.account;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.stmt.Where;
import com.j256.ormlite.table.DatabaseTable;
import one.rewind.android.automator.adapter.Adapter;
import one.rewind.db.DBName;
import one.rewind.db.DaoManager;
import one.rewind.db.model.ModelL;

/**
 * @author maxuefeng [m17793873123@163.com]
 */
@DBName("android_automator")
@DatabaseTable(tableName = "app_accounts")
public class AppAccount extends ModelL {

	// 搜索公众号限流 账号冻结时间间隔
	public static long Default_Search_Public_Account_Frozen_Time = 72 * 3600 * 1000;

	// 点击后出现 "全部消息过于频繁" 账号冻结时间间隔
	public static long Default_Get_Public_Account_Essay_List_Frozen_Time = 24 * 3600 * 1000;

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

	/**
	 * 账号状态
	 */
	public enum Status {

		Normal,   // 正常状态
		Broken,   // 用户名无效 或密码无效
		Blocked,  // 账号被封
		Search_Public_Account_Frozen, // 查询公众号被限流
		Get_Public_Account_Essay_List_Frozen // 获取公众号历史文章被限流
	}

	/**
	 * 获取可用的账号
	 *
	 * @param udid
	 * @param adapter_class_name
	 * @return
	 * @throws Exception
	 */
	public static AppAccount getAccount(String udid, String adapter_class_name) throws Exception {

		long t = System.currentTimeMillis();

		Dao<AppAccount, String> dao = DaoManager.getDao(AppAccount.class);

		return dao.queryBuilder().where()
				.and(
						dao.queryBuilder().where().eq("udid", udid),
						dao.queryBuilder().where().eq("adapter_class_name", adapter_class_name),
						dao.queryBuilder().where().eq("status", "Normal")
								.or().eq("status", "Search_Public_Account_Frozen").and().le("update_time", t - Default_Search_Public_Account_Frozen_Time)
								.or().eq("status", "Get_Public_Account_Essay_List_Frozen").and().le("update_time", t - Default_Get_Public_Account_Essay_List_Frozen_Time)
				)
				.queryForFirst();
	}
}
