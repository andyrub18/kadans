using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Budget.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class TransactionImportKey : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "import_key",
                schema: "budget",
                table: "transactions",
                type: "character varying(64)",
                maxLength: 64,
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "ux_transactions_account_import_key",
                schema: "budget",
                table: "transactions",
                columns: new[] { "account_id", "import_key" },
                unique: true,
                filter: "import_key IS NOT NULL");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ux_transactions_account_import_key",
                schema: "budget",
                table: "transactions");

            migrationBuilder.DropColumn(
                name: "import_key",
                schema: "budget",
                table: "transactions");
        }
    }
}
