package com.hamonsoft.netismaker.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * JDBC 접속 → 프로브 SQL 1회 (스펙 2026-10-02 §5.2). 타임아웃 5초. 비밀번호는 URL이 아니라 Properties로만 넘기고,
 * 실패 메시지에서 비밀번호 문자열을 가린다. DriverManager.setLoginTimeout(전역)은 쓰지 않는다.
 */
@Component
@Profile("api")
public class JdbcDbConnectionTester implements DbConnectionTester {

    @Override
    public Result test(Target t) {
        String url = t.dbType().jdbcUrl(t.host(), t.port(), t.databaseName());
        try (Connection c = DriverManager.getConnection(url, propsFor(t));
             Statement st = c.createStatement()) {
            st.setQueryTimeout(5);
            st.execute(t.dbType().probeSql());
            return new Result(true, "접속 성공");
        } catch (SQLException | RuntimeException e) {
            return new Result(false, "접속 실패: " + mask(String.valueOf(e.getMessage()), t.password()));
        }
    }

    static Properties propsFor(Target t) {
        Properties p = new Properties();
        p.setProperty("user", t.username());
        p.setProperty("password", t.password());
        switch (t.dbType()) {
            case POSTGRESQL -> {
                p.setProperty("connectTimeout", "5");
                p.setProperty("loginTimeout", "5");
                p.setProperty("socketTimeout", "10");
            }
            case MYSQL, MARIADB -> {
                p.setProperty("connectTimeout", "5000");
                p.setProperty("socketTimeout", "10000");
            }
            case ORACLE -> {
                p.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
                p.setProperty("oracle.jdbc.ReadTimeout", "10000");
            }
        }
        return p;
    }

    static String mask(String msg, String secret) {
        if (secret == null || secret.isEmpty()) return msg;
        return msg.replace(secret, "****");
    }
}
