using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Budget.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class RecurringNextOccurrence : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_recurring_transactions_generated_through",
                schema: "budget",
                table: "recurring_transactions");

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "next_occurrence_at",
                schema: "budget",
                table: "recurring_transactions",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "IX_recurring_transactions_next_occurrence_at",
                schema: "budget",
                table: "recurring_transactions",
                column: "next_occurrence_at",
                filter: "is_active = true");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "IX_recurring_transactions_next_occurrence_at",
                schema: "budget",
                table: "recurring_transactions");

            migrationBuilder.DropColumn(
                name: "next_occurrence_at",
                schema: "budget",
                table: "recurring_transactions");

            migrationBuilder.CreateIndex(
                name: "IX_recurring_transactions_generated_through",
                schema: "budget",
                table: "recurring_transactions",
                column: "generated_through",
                filter: "is_active = true");
        }
    }
}
