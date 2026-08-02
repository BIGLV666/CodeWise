package model

type TestCase struct {
	ID       int64
	QuestionID int64
	Input    string
	Expected string
	TimeLimit int
}

type SubmitRecord struct {
	ID         int64
	QuestionID int64
	UserID     int64
	Code       string
	Language   string
	QuestionType string
	TimeLimit  int
}

type JudgeResult struct {
	Status       string `json:"status"`
	ErrorMessage string `json:"errorMessage,omitempty"`
	Log          string `json:"log,omitempty"`
	UserOutput   string `json:"userOutput,omitempty"`
	Expected     string `json:"expectedOutput,omitempty"`
	Input        string `json:"inputData,omitempty"`
	FailIndex    int    `json:"failIndex"`
	TestTotal    int    `json:"testTotal"`
	TimeUsed     int    `json:"timeUsed"`
	MemoryUsed   int    `json:"memoryUsed"`
	TestCaseID   int64  `json:"testCaseId,omitempty"`
}

type ExecuteRequest struct {
	Code      string `json:"code" binding:"required"`
	Language  string `json:"language" binding:"required"`
	Input     string `json:"input"`
	Expected  string `json:"expectedOutput"`
	TimeLimit int    `json:"timeLimit"`
}
