using System;
using System.Collections.Generic;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Tasks.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class Init : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.EnsureSchema(
                name: "tasks");

            migrationBuilder.CreateTable(
                name: "pomodoro_templates",
                schema: "tasks",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_pomodoro_templates", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "recurrence_rules",
                schema: "tasks",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    rrule = table.Column<string>(type: "character varying(512)", maxLength: 512, nullable: false),
                    time_zone_id = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    start_date = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    exceptions = table.Column<List<DateTimeOffset>>(type: "timestamp with time zone[]", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_recurrence_rules", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "pomodoro_template_phases",
                schema: "tasks",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    pomodoro_template_id = table.Column<Guid>(type: "uuid", nullable: false),
                    order = table.Column<int>(type: "integer", nullable: false),
                    type = table.Column<string>(type: "text", nullable: false),
                    duration_minutes = table.Column<int>(type: "integer", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_pomodoro_template_phases", x => x.id);
                    table.ForeignKey(
                        name: "fk_pomodoro_template_phases_pomodoro_templates_pomodoro_templa~",
                        column: x => x.pomodoro_template_id,
                        principalSchema: "tasks",
                        principalTable: "pomodoro_templates",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "todos",
                schema: "tasks",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    title = table.Column<string>(type: "character varying(500)", maxLength: 500, nullable: false),
                    description = table.Column<string>(type: "character varying(4000)", maxLength: 4000, nullable: false),
                    recurrence_rule_id = table.Column<Guid>(type: "uuid", nullable: false),
                    notification_enabled = table.Column<bool>(type: "boolean", nullable: false),
                    notification_lead_time = table.Column<TimeSpan>(type: "interval", nullable: false),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    pomodoro_template_id = table.Column<Guid>(type: "uuid", nullable: true),
                    status = table.Column<string>(type: "text", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_todos", x => x.id);
                    table.ForeignKey(
                        name: "fk_todos_pomodoro_templates_pomodoro_template_id",
                        column: x => x.pomodoro_template_id,
                        principalSchema: "tasks",
                        principalTable: "pomodoro_templates",
                        principalColumn: "id",
                        onDelete: ReferentialAction.SetNull);
                    table.ForeignKey(
                        name: "fk_todos_recurrence_rules_recurrence_rule_id",
                        column: x => x.recurrence_rule_id,
                        principalSchema: "tasks",
                        principalTable: "recurrence_rules",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "pomodoro_runs",
                schema: "tasks",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    todo_id = table.Column<Guid>(type: "uuid", nullable: false),
                    pomodoro_template_id = table.Column<Guid>(type: "uuid", nullable: true),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    status = table.Column<string>(type: "text", nullable: false),
                    current_phase_index = table.Column<int>(type: "integer", nullable: false),
                    started_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    paused_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    completed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_pomodoro_runs", x => x.id);
                    table.ForeignKey(
                        name: "fk_pomodoro_runs_todos_todo_id",
                        column: x => x.todo_id,
                        principalSchema: "tasks",
                        principalTable: "todos",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "todo_occurrences",
                schema: "tasks",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    todo_id = table.Column<Guid>(type: "uuid", nullable: false),
                    occurrence_date = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    is_cancelled = table.Column<bool>(type: "boolean", nullable: false),
                    cancellation_reason = table.Column<string>(type: "character varying(4000)", maxLength: 4000, nullable: false),
                    is_completed = table.Column<bool>(type: "boolean", nullable: false),
                    completed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    remarks = table.Column<string>(type: "character varying(4000)", maxLength: 4000, nullable: false),
                    is_rescheduled = table.Column<bool>(type: "boolean", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_todo_occurrences", x => x.id);
                    table.ForeignKey(
                        name: "fk_todo_occurrences_todos_todo_id",
                        column: x => x.todo_id,
                        principalSchema: "tasks",
                        principalTable: "todos",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "todo_remarks",
                schema: "tasks",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    created_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    remark = table.Column<string>(type: "character varying(2000)", maxLength: 2000, nullable: false),
                    TodoId = table.Column<Guid>(type: "uuid", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_todo_remarks", x => x.Id);
                    table.ForeignKey(
                        name: "fk_todo_remark_todos_todo_id",
                        column: x => x.TodoId,
                        principalSchema: "tasks",
                        principalTable: "todos",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "pomodoro_run_phases",
                schema: "tasks",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    pomodoro_run_id = table.Column<Guid>(type: "uuid", nullable: false),
                    order = table.Column<int>(type: "integer", nullable: false),
                    type = table.Column<string>(type: "text", nullable: false),
                    duration_minutes = table.Column<int>(type: "integer", nullable: false),
                    started_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    completed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_pomodoro_run_phases", x => x.id);
                    table.ForeignKey(
                        name: "fk_pomodoro_run_phases_pomodoro_runs_pomodoro_run_id",
                        column: x => x.pomodoro_run_id,
                        principalSchema: "tasks",
                        principalTable: "pomodoro_runs",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_run_phases_run_order",
                schema: "tasks",
                table: "pomodoro_run_phases",
                columns: new[] { "pomodoro_run_id", "order" });

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_runs_todo_id_started_at_desc",
                schema: "tasks",
                table: "pomodoro_runs",
                columns: new[] { "todo_id", "started_at" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_runs_user_id_status_started_at_desc",
                schema: "tasks",
                table: "pomodoro_runs",
                columns: new[] { "user_id", "status", "started_at" },
                descending: new[] { false, false, true });

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_template_phases_template_order",
                schema: "tasks",
                table: "pomodoro_template_phases",
                columns: new[] { "pomodoro_template_id", "order" });

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_templates_user_id_created_at_desc",
                schema: "tasks",
                table: "pomodoro_templates",
                columns: new[] { "user_id", "created_at" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "ix_todo_occurrences_occurrence_date_active",
                schema: "tasks",
                table: "todo_occurrences",
                column: "occurrence_date",
                filter: "NOT is_cancelled AND NOT is_completed");

            migrationBuilder.CreateIndex(
                name: "ix_todo_occurrences_todo_id_occurrence_date",
                schema: "tasks",
                table: "todo_occurrences",
                columns: new[] { "todo_id", "occurrence_date" });

            migrationBuilder.CreateIndex(
                name: "IX_todo_remarks_TodoId",
                schema: "tasks",
                table: "todo_remarks",
                column: "TodoId");

            migrationBuilder.CreateIndex(
                name: "IX_todos_pomodoro_template_id",
                schema: "tasks",
                table: "todos",
                column: "pomodoro_template_id");

            migrationBuilder.CreateIndex(
                name: "IX_todos_recurrence_rule_id",
                schema: "tasks",
                table: "todos",
                column: "recurrence_rule_id");

            migrationBuilder.CreateIndex(
                name: "ix_todos_user_id_created_at_desc",
                schema: "tasks",
                table: "todos",
                columns: new[] { "user_id", "created_at" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "ix_todos_user_id_created_at_id_active",
                schema: "tasks",
                table: "todos",
                columns: new[] { "user_id", "created_at", "id" },
                descending: new[] { false, true, false },
                filter: "status IN ('Scheduled', 'Started')");

            migrationBuilder.CreateIndex(
                name: "ix_todos_user_id_id",
                schema: "tasks",
                table: "todos",
                columns: new[] { "user_id", "id" });

            migrationBuilder.CreateIndex(
                name: "ix_todos_user_id_status_created_at_desc",
                schema: "tasks",
                table: "todos",
                columns: new[] { "user_id", "status", "created_at" },
                descending: new[] { false, false, true });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "pomodoro_run_phases",
                schema: "tasks");

            migrationBuilder.DropTable(
                name: "pomodoro_template_phases",
                schema: "tasks");

            migrationBuilder.DropTable(
                name: "todo_occurrences",
                schema: "tasks");

            migrationBuilder.DropTable(
                name: "todo_remarks",
                schema: "tasks");

            migrationBuilder.DropTable(
                name: "pomodoro_runs",
                schema: "tasks");

            migrationBuilder.DropTable(
                name: "todos",
                schema: "tasks");

            migrationBuilder.DropTable(
                name: "pomodoro_templates",
                schema: "tasks");

            migrationBuilder.DropTable(
                name: "recurrence_rules",
                schema: "tasks");
        }
    }
}
