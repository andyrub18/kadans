using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Budget.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class CategoryLimitPeriods : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.RenameColumn(
                name: "monthly_limit",
                schema: "budget",
                table: "category_budgets",
                newName: "amount");

            migrationBuilder.AddColumn<int>(
                name: "alerted_level",
                schema: "budget",
                table: "category_budgets",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "alerted_period_start",
                schema: "budget",
                table: "category_budgets",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "period",
                schema: "budget",
                table: "category_budgets",
                type: "character varying(8)",
                maxLength: 8,
                nullable: false,
                // The limits set before periods existed were monthly.
                defaultValue: "Month");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "alerted_level",
                schema: "budget",
                table: "category_budgets");

            migrationBuilder.DropColumn(
                name: "alerted_period_start",
                schema: "budget",
                table: "category_budgets");

            migrationBuilder.DropColumn(
                name: "period",
                schema: "budget",
                table: "category_budgets");

            migrationBuilder.RenameColumn(
                name: "amount",
                schema: "budget",
                table: "category_budgets",
                newName: "monthly_limit");
        }
    }
}
