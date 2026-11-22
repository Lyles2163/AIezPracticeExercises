-- 2026-11-04 OJ 最小演示数据：一道 A+B 题（2 个示例用例 + 2 个隐藏用例）
--
-- 前置：先执行 2026-11-04-oj.sql 建表。
-- 执行后即可用 Postman/前端试：
--   GET  /practice/oj/problems
--   GET  /practice/oj/problem/1
--   POST /practice/oj/submit    （Header 需要网关注入的 X-User-Name）
--   GET  /practice/oj/submission/{id}
--
-- 重复执行会因 oj_problem 没有唯一键而产生重复题目，只跑一次。

INSERT INTO oj_problem (subject_id, title, description, template_code, difficulty,
                        time_limit_ms, memory_limit_mb, status)
VALUES (0, 'A+B Problem',
        '读入两个整数 a 和 b（空格分隔），输出它们的和。\n\n输入格式：一行两个整数，范围 -10^9 ~ 10^9。\n输出格式：一个整数。',
        'import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner in = new Scanner(System.in);\n        // TODO: 读入 a、b 并输出 a + b\n    }\n}\n',
        1, 2000, 256, 1);

-- seq 1、2 是向用户展示的示例；seq 3、4 是隐藏用例（is_sample=0）
INSERT INTO oj_testcase (problem_id, seq, input_data, expected_output, is_sample) VALUES
  (1, 1, '1 2\n',    '3\n',    1),
  (1, 2, '10 20\n',  '30\n',   1),
  (1, 3, '-5 5\n',   '0\n',    0),
  (1, 4, '100000 234567\n', '334567\n', 0);
