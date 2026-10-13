using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Tasks.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class ReminderAtStart : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "start_notified_at",
                schema: "tasks",
                table: "todo_occurrences",
                type: "timestamp with time zone",
                nullable: true);

            // Occurrences already under way had their reminder: none gets a late "starts now" once this is deployed.
            migrationBuilder.Sql(
                "UPDATE tasks.todo_occurrences SET start_notified_at = now() WHERE scheduled_at <= now() AND notify_at IS NOT NULL;");

            migrationBuilder.AddColumn<Guid>(
                name: "occurrence_id",
                schema: "tasks",
                table: "pomodoro_runs",
                type: "uuid",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "ix_todo_occurrences_start_due",
                schema: "tasks",
                table: "todo_occurrences",
                column: "scheduled_at",
                filter: "status = 'Pending' AND start_notified_at IS NULL AND notify_at IS NOT NULL");

            migrationBuilder.CreateIndex(
                name: "IX_pomodoro_runs_occurrence_id",
                schema: "tasks",
                table: "pomodoro_runs",
                column: "occurrence_id");

            migrationBuilder.AddForeignKey(
                name: "FK_pomodoro_runs_todo_occurrences_occurrence_id",
                schema: "tasks",
                table: "pomodoro_runs",
                column: "occurrence_id",
                principalSchema: "tasks",
                principalTable: "todo_occurrences",
                principalColumn: "id",
                onDelete: ReferentialAction.SetNull);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "FK_pomodoro_runs_todo_occurrences_occurrence_id",
                schema: "tasks",
                table: "pomodoro_runs");

            migrationBuilder.DropIndex(
                name: "ix_todo_occurrences_start_due",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.DropIndex(
                name: "IX_pomodoro_runs_occurrence_id",
                schema: "tasks",
                table: "pomodoro_runs");

            migrationBuilder.DropColumn(
                name: "start_notified_at",
                schema: "tasks",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "occurrence_id",
                schema: "tasks",
                table: "pomodoro_runs");
        }
    }
}
