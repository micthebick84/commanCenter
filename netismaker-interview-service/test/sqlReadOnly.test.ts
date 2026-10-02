import { describe, expect, it } from 'vitest';
import { checkReadOnlySql, type SqlDialect } from '../src/sdk/sqlReadOnly.js';

const ok = (sql: string, d: SqlDialect) => expect(checkReadOnlySql(sql, d), `${d}: ${sql}`).toEqual({ ok: true });
const no = (sql: string, d: SqlDialect) => expect(checkReadOnlySql(sql, d).ok, `${d}: ${sql}`).toBe(false);
const ALL: SqlDialect[] = ['postgres', 'mysql', 'oracle'];

describe('checkReadOnlySql — 허용', () => {
  it('조회문 기본형 (3 방언 공통)', () => {
    for (const d of ALL) {
      for (const sql of [
        'SELECT 1',
        'select * from users where id = 3;',
        '  WITH t AS (SELECT 1 AS a) SELECT a FROM t',
        'SELECT updated_at, created_by, set_id FROM orders',
        "SELECT 'DELETE FROM x; DROP TABLE y' AS note",
        'SELECT REPLACE(name, \'a\', \'b\') FROM t',
        'EXPLAIN SELECT * FROM t',
        "SELECT * FROM t WHERE name = 'it''s'",
        'SELECT /* UPDATE 주석 */ 1',
        'SELECT 1 -- DELETE 주석\n',
      ]) ok(sql, d);
    }
  });

  it('방언별 조회문', () => {
    ok('SHOW TABLES', 'mysql');
    ok('DESC users', 'mysql');
    ok('SELECT `select` FROM `order`', 'mysql');
    ok('SELECT 1 # 주석 DELETE', 'mysql');
    ok('SHOW search_path', 'postgres');
    ok('VALUES (1), (2)', 'postgres');
    ok('TABLE users', 'postgres');
    ok('SELECT $$a;b$$', 'postgres');
    ok('SELECT "update" FROM t', 'postgres');
    ok('SELECT * FROM dual', 'oracle');
    ok('SELECT "DELETE" FROM t', 'oracle');
  });
});

describe('checkReadOnlySql — 거부', () => {
  it('DML/DDL/권한/트랜잭션', () => {
    for (const d of ALL) {
      for (const sql of [
        'DELETE FROM t',
        'UPDATE t SET a = 1',
        'INSERT INTO t VALUES (1)',
        'MERGE INTO t USING s ON (1=1) WHEN MATCHED THEN UPDATE SET a = 1',
        'CREATE TABLE x (a int)',
        'ALTER TABLE t ADD c int',
        'DROP TABLE t',
        'TRUNCATE t',
        'GRANT SELECT ON t TO u',
        'COMMIT',
        'CALL p()',
        '',
        '   ',
      ]) no(sql, d);
    }
  });

  it('조회문 모양 안에 숨긴 쓰기', () => {
    for (const d of ALL) {
      for (const sql of [
        'SELECT 1; DELETE FROM t',
        'SELECT 1;; ',
        'WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x',
        'SELECT * INTO new_t FROM t',
        'SELECT * FROM t FOR UPDATE',
        'EXPLAIN ANALYZE SELECT 1',
        'SELECT 1 /* 닫히지 않은 주석',
        "SELECT 'unterminated",
        "SELECT 'a\\' ; DELETE FROM t; --'",   // Review Focus 4: 백슬래시가 든 문자열은 거부
      ]) no(sql, d);
    }
  });

  it('부작용·파일 접근 함수', () => {
    no("SELECT pg_terminate_backend(123)", 'postgres');
    no("SELECT set_config('x', 'y', false)", 'postgres');
    no("SELECT pg_read_file('/etc/passwd')", 'postgres');
    no("SELECT lo_import('/tmp/x')", 'postgres');
    no("SELECT dblink_exec('...')", 'postgres');
    no("SELECT nextval('seq')", 'postgres');
    no('SELECT pg_sleep(100)', 'postgres');
    no("SELECT LOAD_FILE('/etc/passwd')", 'mysql');
    no('SELECT SLEEP(100)', 'mysql');
    no('SELECT BENCHMARK(1e9, MD5(1))', 'mysql');
    no('SELECT DBMS_PIPE.RECEIVE_MESSAGE(1) FROM dual', 'oracle');
    no("SELECT UTL_HTTP.REQUEST('http://x') FROM dual", 'oracle');
  });

  it('방언별 주석·인용 우회 (Review Focus 4)', () => {
    no('SELECT 1 /*! ; DELETE FROM t */', 'mysql');        // MySQL 실행 주석
    no('SELECT 1 /*M! DELETE FROM t */', 'mysql');         // MariaDB 실행 주석
    no('SELECT 1 # x\n; DELETE FROM t', 'mysql');           // # 주석 뒤 줄바꿈 다음은 검사
    no('SELECT 1 # 1; DELETE FROM t', 'postgres');          // PG에서 #은 연산자 — 숨김 금지
    no('SELECT 1--1; DELETE FROM t', 'mysql');              // MySQL '--' 뒤 공백 없으면 주석 아님
    no('SELECT $a$ ; DELETE FROM t; $a$', 'mysql');         // MySQL은 $$ 문자열이 없다
    no('SELECT $$x', 'postgres');                           // 닫히지 않은 달러 인용
    no('SELECT `a` ; DELETE FROM t', 'mysql');
    no('SELECT "a" ; DELETE FROM t', 'postgres');
  });

  it('I-1: PG 비ASCII 식별자 + 달러 인용', () => {
    // Non-ASCII identifier followed by $$ should be rejected
    no('SELECT é$$ x ; y $$', 'postgres');
    no('SELECT 가격$$ DELETE FROM t $$', 'postgres');
    // Plain non-ASCII identifier should be allowed
    ok('SELECT 가격 FROM t', 'postgres');
  });

  it('I-2: Oracle q-quote 대안 인용', () => {
    // Oracle q'[...]' should be rejected
    no("SELECT q'[a' ; DELETE FROM t --]'", 'oracle');
    no("SELECT Q'[x]'", 'oracle');
    no("SELECT nq'[test]'", 'oracle');
    no("SELECT Nq'[test]'", 'oracle');
    no("SELECT NQ'[test]'", 'oracle');
    // Regular strings and identifiers should be allowed
    ok("SELECT 'q' FROM dual", 'oracle');
    ok('SELECT seq FROM t', 'oracle');
    ok("SELECT q_col FROM t", 'oracle');
  });

  it('I-3: PG SQL-문자열 함수', () => {
    // Functions that execute SQL strings should be rejected
    no("SELECT query_to_xml('SELECT 1', true, true, '')", 'postgres');
    no("SELECT query_to_xml_and_xmlschema('SELECT 1', true, true, '')", 'postgres');
    no("SELECT query_to_xmlschema('SELECT 1')", 'postgres');
    no("SELECT cursor_to_xml('cur')", 'postgres');
    no("SELECT ts_stat('SELECT 1')", 'postgres');
    no("SELECT ts_rewrite('SELECT 1')", 'postgres');
    no("SELECT pg_notify('chan', 'msg')", 'postgres');
    no('SELECT pg_sleep_for(interval 1)', 'postgres');
    no('SELECT pg_sleep_until(now())', 'postgres');
    no('SELECT pg_logical_something()', 'postgres');
  });
});
