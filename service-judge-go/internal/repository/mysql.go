package repository

import (
	"context"
	"database/sql"

	_ "github.com/go-sql-driver/mysql"
	"codewise-judge/internal/model"
)

type MySQL struct { DB *sql.DB }

func OpenMySQL(dsn string) (*MySQL, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil { return nil, err }
	if err = db.Ping(); err != nil { db.Close(); return nil, err }
	return &MySQL{DB: db}, nil
}

func (repo *MySQL) FindSubmit(ctx context.Context, id int64) (model.SubmitRecord, error) {
	var record model.SubmitRecord
	err := repo.DB.QueryRowContext(ctx, `
		SELECT s.submit_record_id, s.question_id, s.user_id, s.submit_content,
		       s.language, COALESCE(q.question_type, 'ACM'), COALESCE(q.time_limit, 2000)
		FROM submit_record s LEFT JOIN question q ON q.question_id = s.question_id
		WHERE s.submit_record_id = ?`, id).Scan(
		&record.ID, &record.QuestionID, &record.UserID, &record.Code,
		&record.Language, &record.QuestionType, &record.TimeLimit,
	)
	return record, err
}

func (repo *MySQL) FindTestCases(ctx context.Context, questionID int64) ([]model.TestCase, error) {
	rows, err := repo.DB.QueryContext(ctx, `
		SELECT case_id, question_id, input_data, expected_output, COALESCE(time_limit, 0)
		FROM test_case WHERE question_id = ? ORDER BY sort_order, case_id`, questionID)
	if err != nil { return nil, err }
	defer rows.Close()

	var cases []model.TestCase
	for rows.Next() {
		var item model.TestCase
		if err := rows.Scan(&item.ID, &item.QuestionID, &item.Input, &item.Expected, &item.TimeLimit); err != nil {
			return nil, err
		}
		cases = append(cases, item)
	}
	return cases, rows.Err()
}

func (repo *MySQL) SaveResult(ctx context.Context, submitID int64, code string, result model.JudgeResult) (int64, error) {
	_, err := repo.DB.ExecContext(ctx,
		`UPDATE submit_record SET submit_status = ?, judge_status = ?, time_used = ?, memory_used = ? WHERE submit_record_id = ?`,
		result.Status, "success", result.TimeUsed, result.MemoryUsed, submitID)
	if err != nil { return 0, err }

	execResult, err := repo.DB.ExecContext(ctx, `
		INSERT INTO judge_record
		(submit_record_id, test_case_id, submit_status, error_msg, log, user_output,
		 test_total, fail_index, code, input_data, expected_output, time_used, memory_used)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		submitID, result.TestCaseID, result.Status, result.ErrorMessage, result.Log,
		result.UserOutput, result.TestTotal, result.FailIndex, code, result.Input,
		result.Expected, result.TimeUsed, result.MemoryUsed)
	if err != nil { return 0, err }
	return execResult.LastInsertId()
}
