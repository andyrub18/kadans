using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Tasks.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class PomodoroSessionEnd : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "finish_by",
                schema: "tasks",
                table: "pomodoro_runs",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "paused_seconds",
                schema: "tasks",
                table: "pomodoro_run_phases",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_runs_finish_due",
                schema: "tasks",
                table: "pomodoro_runs",
                column: "finish_by",
                filter: "status IN ('Active', 'Paused')");

            // Sessions already running get the default end, counted from their start: one left running for days
            // finishes at the next pass, silently (its end is long past).
            migrationBuilder.Sql(
                "UPDATE tasks.pomodoro_runs SET finish_by = started_at + interval '12 hours' WHERE status IN ('Active', 'Paused') AND finish_by IS NULL;"
            );
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ix_pomodoro_runs_finish_due",
                schema: "tasks",
                table: "pomodoro_runs");

            migrationBuilder.DropColumn(
                name: "finish_by",
                schema: "tasks",
                table: "pomodoro_runs");

            migrationBuilder.DropColumn(
                name: "paused_seconds",
                schema: "tasks",
                table: "pomodoro_run_phases");
        }
    }
}
