package one.rewind.android.automator.test.api;

import org.junit.Test;

import static spark.Spark.get;
import static spark.Spark.port;

/**
 * @author maxuefeng[m17793873123@163.com]
 */
public class SparkTest {


    @Test
    public void testStart() {

        port(8080);

        get("/hello", (req, res) -> "Hello World");
    }

    public static void main(String[] args) {

        port(8080);

        get("/hello", (req, res) -> "Hello World");
    }
}
