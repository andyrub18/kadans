using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Tasks.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class PomodoroTiming : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<bool>(
                name: "auto_advance",
                schema: "tasks",
                table: "pomodoro_runs",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<TimeSpan>(
                name: "paused_remaining",
                schema: "tasks",
                table: "pomodoro_runs",
                type: "interval",
                nullable: true);

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "phase_ends_at",
                schema: "tasks",
                table: "pomodoro_runs",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "ix_pomodoro_runs_auto_advance_due",
                schema: "tasks",
                table: "pomodoro_runs",
                column: "phase_ends_at",
                filter: "status = 'Active' AND auto_advance");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ix_pomodoro_runs_auto_advance_due",
                schema: "tasks",
                table: "pomodoro_runs");

            migrationBuilder.DropColumn(
                name: "auto_advance",
                schema: "tasks",
                table: "pomodoro_runs");

            migrationBuilder.DropColumn(
                name: "paused_remaining",
                schema: "tasks",
                table: "pomodoro_runs");

            migrationBuilder.DropColumn(
                name: "phase_ends_at",
                schema: "tasks",
                table: "pomodoro_runs");
        }
    }
}
