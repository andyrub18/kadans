using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Api.Migrations
{
    /// <inheritdoc />
    public partial class TodoOccurrenceState : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "cancellation_reason",
                table: "todo_occurrences",
                type: "character varying(4000)",
                maxLength: 4000,
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "completed_at",
                table: "todo_occurrences",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<bool>(
                name: "is_cancelled",
                table: "todo_occurrences",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<bool>(
                name: "is_completed",
                table: "todo_occurrences",
                type: "boolean",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<bool>(
                name: "is_rescheduled",
                table: "todo_occurrences",
                type: "boolean",
                nullable: false,
                defaultValue: false);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "cancellation_reason",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "completed_at",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "is_cancelled",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "is_completed",
                table: "todo_occurrences");

            migrationBuilder.DropColumn(
                name: "is_rescheduled",
                table: "todo_occurrences");
        }
    }
}
