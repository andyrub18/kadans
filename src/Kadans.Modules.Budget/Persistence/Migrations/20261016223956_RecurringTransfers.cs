using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Budget.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class RecurringTransfers : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(
                name: "transfer_account_id",
                schema: "budget",
                table: "recurring_transactions",
                type: "uuid",
                nullable: true);

            migrationBuilder.AddColumn<decimal>(
                name: "transfer_amount",
                schema: "budget",
                table: "recurring_transactions",
                type: "numeric(16,2)",
                precision: 16,
                scale: 2,
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "IX_recurring_transactions_transfer_account_id",
                schema: "budget",
                table: "recurring_transactions",
                column: "transfer_account_id");

            migrationBuilder.AddForeignKey(
                name: "fk_recurring_transactions_accounts_transfer_account_id",
                schema: "budget",
                table: "recurring_transactions",
                column: "transfer_account_id",
                principalSchema: "budget",
                principalTable: "accounts",
                principalColumn: "id",
                onDelete: ReferentialAction.Cascade);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "fk_recurring_transactions_accounts_transfer_account_id",
                schema: "budget",
                table: "recurring_transactions");

            migrationBuilder.DropIndex(
                name: "IX_recurring_transactions_transfer_account_id",
                schema: "budget",
                table: "recurring_transactions");

            migrationBuilder.DropColumn(
                name: "transfer_account_id",
                schema: "budget",
                table: "recurring_transactions");

            migrationBuilder.DropColumn(
                name: "transfer_amount",
                schema: "budget",
                table: "recurring_transactions");
        }
    }
}
