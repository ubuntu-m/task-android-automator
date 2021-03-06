package one.rewind.android.automator.log;

import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import one.rewind.db.annotation.DBName;
import one.rewind.db.model.ModelL;

/**
 * @author maxuefeng [m17793873123@163.com]
 */
@DBName("android_automator")
@DatabaseTable(tableName = "sys_logs")
public class SysLog extends ModelL {

    public SysLog(){}

    @DatabaseField(dataType = DataType.STRING, columnDefinition = "MEDIUMTEXT")
    public String content; // 保存出错的堆栈信息

    public SysLog(String content) {
        this.content = content;
    }

    public static void log(String content) throws Exception {
        SysLog log = new SysLog(content);
        log.insert();
    }
}
