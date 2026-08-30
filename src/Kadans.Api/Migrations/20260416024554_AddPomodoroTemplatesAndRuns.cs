using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddPomodoroTemplatesAndRuns : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(
                name: "pomodoro_template_id",
                table: "todos",
                type: "uuid",
                nullable: true);

            migrationBuilder.CreateTable(
                name: "pomodoro_runs",
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
                        name: "fk_pomodoro_runs_asp_net_users_user_id",
                        column: x => x.user_id,
                        principalTable: "asp_net_users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                    table.ForeignKey(
                        name: "fk_pomodoro_runs_todos_todo_id",
                        column: x => x.todo_id,
                        principalTable: "todos",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "pomodoro_templates",
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
                    table.ForeignKey(
                        name: "fk_pomodoro_templates_asp_net_users_user_id",
                        column: x => x.user_id,
                        principalTable: "asp_net_users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "pomodoro_run_phases",
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
                        principalTable: "pomodoro_runs",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "pomodoro_template_phases",
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
                        principalTable: "pomodoro_templates",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_todos_pomodoro_template_id",
                table: "todos",
                column: "pomodoro_template_id");

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_run_phases_run_order",
                table: "pomodoro_run_phases",
                columns: new[] { "pomodoro_run_id", "order" });

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_runs_todo_id_started_at_desc",
                table: "pomodoro_runs",
                columns: new[] { "todo_id", "started_at" },
                descending: new[] { false, true });

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_runs_user_id_status_started_at_desc",
                table: "pomodoro_runs",
                columns: new[] { "user_id", "status", "started_at" },
                descending: new[] { false, false, true });

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_template_phases_template_order",
                table: "pomodoro_template_phases",
                columns: new[] { "pomodoro_template_id", "order" });

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_templates_user_id_created_at_desc",
                table: "pomodoro_templates",
                columns: new[] { "user_id", "created_at" },
                descending: new[] { false, true });

            migrationBuilder.AddForeignKey(
                name: "fk_todos_pomodoro_templates_pomodoro_template_id",
                table: "todos",
                column: "pomodoro_template_id",
                principalTable: "pomodoro_templates",
                principalColumn: "id",
                onDelete: ReferentialAction.SetNull);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "fk_todos_pomodoro_templates_pomodoro_template_id",
                table: "todos");

            migrationBuilder.DropTable(
                name: "pomodoro_run_phases");

            migrationBuilder.DropTable(
                name: "pomodoro_template_phases");

            migrationBuilder.DropTable(
                name: "pomodoro_runs");

            migrationBuilder.DropTable(
                name: "pomodoro_templates");

            migrationBuilder.DropIndex(
                name: "IX_todos_pomodoro_template_id",
                table: "todos");

            migrationBuilder.DropColumn(
                name: "pomodoro_template_id",
                table: "todos");
        }
    }
}
